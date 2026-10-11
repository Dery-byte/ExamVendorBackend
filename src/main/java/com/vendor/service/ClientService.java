package com.vendor.service;

import com.vendor.model.Client;
import com.vendor.model.IssuedKey;
import com.vendor.model.Payment;
import com.vendor.repository.ClientRepository;
import com.vendor.repository.IssuedKeyRepository;
import com.vendor.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Clients and their terms. Anything that changes what a client's portal should do (payment, new
 * terms, suspension) issues a new signed key; the portal installs it at its next check-in.
 */
@Service
public class ClientService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClientRepository clients;
    private final IssuedKeyRepository keys;
    private final PaymentRepository payments;
    private final KeySigner signer;
    private final ActivityLog activity;

    public ClientService(ClientRepository clients, IssuedKeyRepository keys, PaymentRepository payments,
                         KeySigner signer, ActivityLog activity) {
        this.clients = clients;
        this.keys = keys;
        this.payments = payments;
        this.signer = signer;
        this.activity = activity;
    }

    /** A new client and its check-in token, which is shown only this once. */
    public record Created(Client client, String token) {}

    public Client get(Long id) {
        return clients.findById(id).orElseThrow(() -> new NoSuchElementException("No such client."));
    }

    @Transactional
    public Created create(ClientForm f, String actor) {
        String code = Objects.toString(f.getCode(), "").trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) throw new IllegalArgumentException("Enter the institution code.");
        if (clients.existsByCodeIgnoreCase(code)) throw new IllegalArgumentException("There is already a client with the code " + code + ".");
        Client c = new Client();
        c.setCode(code);
        apply(c, f);
        c.setCreatedAt(LocalDateTime.now().withNano(0));
        String token = newToken();
        setToken(c, token);
        clients.save(c);
        activity.record(c.getId(), actor, "Client added", c.getName() + " (" + code + "), paid until " + c.getPaidUntil());
        issue(c, "Client added", actor);
        return new Created(c, token);
    }

    @Transactional
    public void update(Long id, ClientForm f, String actor) {
        Client c = get(id);
        String before = terms(c);
        apply(c, f);
        clients.save(c);
        String after = terms(c);
        activity.record(id, actor, "Client details changed", null);
        if (!before.equals(after)) issue(c, "Terms changed: " + after, actor);
    }

    @Transactional
    public Payment recordPayment(Long id, PaymentForm f, String actor) {
        Client c = get(id);
        if (f.getCoversUntil().isBefore(f.getPaidOn()))
            throw new IllegalArgumentException("The date it covers to can't be before the day it was paid.");
        Payment p = new Payment();
        p.setClient(c);
        p.setAmount(f.getAmount());
        p.setCurrency(f.getCurrency().toUpperCase(Locale.ROOT));
        p.setPaidOn(f.getPaidOn());
        p.setCoversUntil(f.getCoversUntil());
        p.setMethod(blankToNull(f.getMethod()));
        p.setReference(blankToNull(f.getReference()));
        p.setNotes(blankToNull(f.getNotes()));
        p.setRecordedBy(actor);
        p.setRecordedAt(LocalDateTime.now().withNano(0));
        payments.save(p);
        String what = p.getCurrency() + " " + p.getAmount().toPlainString() + " paid " + p.getPaidOn()
                + (p.getReference() == null ? "" : " (ref " + p.getReference() + ")");
        activity.record(id, actor, "Payment recorded", what + ", covers to " + p.getCoversUntil());
        if (f.getCoversUntil().isAfter(c.getPaidUntil())) {
            c.setPaidUntil(f.getCoversUntil());
            clients.save(c);
            issue(c, "Payment recorded: paid until " + c.getPaidUntil(), actor);
        }
        return p;
    }

    @Transactional
    public void suspend(Long id, String reason, String actor) {
        String r = Objects.toString(reason, "").trim();
        if (r.isEmpty()) throw new IllegalArgumentException("Give a reason for suspending this client.");
        if (r.length() > 300) throw new IllegalArgumentException("Keep the reason under 300 characters.");
        Client c = get(id);
        c.setSuspended(true);
        c.setSuspendReason(r);
        clients.save(c);
        issue(c, "Suspended: " + r, actor);
    }

    @Transactional
    public void resume(Long id, String actor) {
        Client c = get(id);
        if (!c.isSuspended()) throw new IllegalArgumentException("This client is not suspended.");
        c.setSuspended(false);
        c.setSuspendReason(null);
        clients.save(c);
        issue(c, "Suspension lifted", actor);
    }

    /** Replaces the client's check-in token; the old one stops working at once. Returns the new token. */
    @Transactional
    public String rotateToken(Long id, String actor) {
        Client c = get(id);
        String token = newToken();
        setToken(c, token);
        clients.save(c);
        activity.record(id, actor, "Check-in token replaced", "New token ends …" + c.getTokenHint());
        return token;
    }

    /** Signs and stores a key for the client's current terms. */
    IssuedKey issue(Client c, String reason, String actor) {
        long seq = System.currentTimeMillis();
        Optional<IssuedKey> last = keys.findFirstByClient_IdOrderBySeqDesc(c.getId());
        if (last.isPresent() && last.get().getSeq() >= seq) seq = last.get().getSeq() + 1;
        String keyId = UUID.randomUUID().toString();
        String note = c.isSuspended() ? c.getSuspendReason() : null;
        String raw = signer.sign(new KeySigner.Terms(keyId, c.getCode(), c.getName(), c.getPlan(), LocalDate.now(),
                c.getPaidUntil(), c.getGraceDays(), c.getRestrictedDays(), seq, c.isSuspended(), note));
        IssuedKey k = new IssuedKey();
        k.setClient(c);
        k.setKeyId(keyId);
        k.setSeq(seq);
        k.setPlan(c.getPlan());
        k.setPaidUntil(c.getPaidUntil());
        k.setGraceDays(c.getGraceDays());
        k.setRestrictedDays(c.getRestrictedDays());
        k.setSuspended(c.isSuspended());
        k.setNote(note);
        k.setReason(reason.length() > 200 ? reason.substring(0, 199) + "…" : reason);
        k.setRaw(raw);
        k.setIssuedAt(LocalDateTime.now().withNano(0));
        k.setIssuedBy(actor);
        keys.save(k);
        activity.record(c.getId(), actor, "Key issued", k.getReason());
        return k;
    }

    private static void apply(Client c, ClientForm f) {
        c.setName(f.getName().trim());
        c.setContactName(blankToNull(f.getContactName()));
        c.setContactEmail(blankToNull(f.getContactEmail()));
        c.setContactPhone(blankToNull(f.getContactPhone()));
        c.setPortalUrl(blankToNull(f.getPortalUrl()));
        c.setPlan(f.getPlan().trim());
        c.setPaidUntil(f.getPaidUntil());
        c.setGraceDays(f.getGraceDays());
        c.setRestrictedDays(f.getRestrictedDays());
        c.setNotes(blankToNull(f.getNotes()));
        c.setAnnualPrice(f.getAnnualPrice() == null || f.getAnnualPrice().signum() == 0 ? null : f.getAnnualPrice());
        c.setCurrency(f.getCurrency() == null || f.getCurrency().isBlank() ? "GHS" : f.getCurrency().trim().toUpperCase(Locale.ROOT));
        c.setBillingEmail(blankToNull(f.getBillingEmail()));
    }

    /** What goes into a key, to tell whether an edit needs a new one. */
    private static String terms(Client c) {
        return c.getName() + "|" + c.getPlan() + "|paid until " + c.getPaidUntil() + "|grace " + c.getGraceDays()
                + "|read-only " + c.getRestrictedDays();
    }

    public static ClientForm formOf(Client c) {
        ClientForm f = new ClientForm();
        f.setCode(c.getCode());
        f.setName(c.getName());
        f.setContactName(c.getContactName());
        f.setContactEmail(c.getContactEmail());
        f.setContactPhone(c.getContactPhone());
        f.setPortalUrl(c.getPortalUrl());
        f.setPlan(c.getPlan());
        f.setPaidUntil(c.getPaidUntil());
        f.setGraceDays(c.getGraceDays());
        f.setRestrictedDays(c.getRestrictedDays());
        f.setNotes(c.getNotes());
        f.setAnnualPrice(c.getAnnualPrice());
        f.setCurrency(c.getCurrency());
        f.setBillingEmail(c.getBillingEmail());
        return f;
    }

    // ── Tokens ───────────────────────────────────────────────────────────

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "vpt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static void setToken(Client c, String token) {
        c.setTokenHash(sha256(token));
        c.setTokenHint(token.substring(token.length() - 4));
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
