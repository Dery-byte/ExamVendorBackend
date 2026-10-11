package com.vendor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.vendor.model.Client;
import com.vendor.model.Invoice;
import com.vendor.model.Payment;
import com.vendor.model.PaystackCheckout;
import com.vendor.repository.ClientRepository;
import com.vendor.repository.InvoiceRepository;
import com.vendor.repository.PaystackCheckoutRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/**
 * Invoices and paying them online. Paying an invoice records the payment and moves the client's
 * paid-until date on, which issues a new key (ClientService.recordPayment); the client's portal picks
 * it up at its next check-in. A payment is only recorded after Paystack itself confirms it, and only once.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Renewal invoices go out this many days before the paid period ends. */
    public static final int RENEWAL_NOTICE_DAYS = 30;
    public static final String PAYSTACK_ACTOR = "Paystack";

    private final InvoiceRepository invoices;
    private final PaystackCheckoutRepository checkouts;
    private final ClientRepository clients;
    private final ClientService clientService;
    private final PaystackClient paystack;
    private final ActivityLog activity;

    @Value("${app.public-url:http://localhost:8090}") private String publicUrl;

    public InvoiceService(InvoiceRepository invoices, PaystackCheckoutRepository checkouts, ClientRepository clients,
                          ClientService clientService, PaystackClient paystack, ActivityLog activity) {
        this.invoices = invoices;
        this.checkouts = checkouts;
        this.clients = clients;
        this.clientService = clientService;
        this.paystack = paystack;
        this.activity = activity;
    }

    public String payUrl(Invoice i) { return publicUrl.replaceAll("/+$", "") + "/pay/" + i.getPayToken(); }

    public Invoice byPayToken(String token) {
        return invoices.findByPayToken(token).orElseThrow(() -> new NoSuchElementException("No such invoice."));
    }

    // ── Raising and cancelling ───────────────────────────────────────────

    @Transactional
    public Invoice create(Long clientId, InvoiceForm f, String actor) {
        Client c = clientService.get(clientId);
        if (invoices.existsByClient_IdAndStatus(clientId, Invoice.Status.OPEN))
            throw new IllegalArgumentException("This client already has an open invoice. Cancel it first.");
        if (!f.getCoversUntil().isAfter(c.getPaidUntil()))
            throw new IllegalArgumentException("It must renew the subscription past " + c.getPaidUntil() + ", the date already paid to.");
        return save(c, f.getAmount(), f.getCurrency(), f.getCoversUntil(), f.getDueDate(), f.getNotes(), actor);
    }

    @Transactional
    public void cancel(Long invoiceId, String actor) {
        Invoice i = invoices.findById(invoiceId).orElseThrow(() -> new NoSuchElementException("No such invoice."));
        if (i.getStatus() != Invoice.Status.OPEN) throw new IllegalArgumentException("Only an open invoice can be cancelled.");
        i.setStatus(Invoice.Status.CANCELLED);
        invoices.save(i);
        activity.record(i.getClient().getId(), actor, "Invoice cancelled", i.getNumber());
    }

    /** Raises a renewal invoice for clients with a yearly price whose paid period ends within 30 days. */
    @Scheduled(cron = "${app.billing.renewal-cron:0 15 6 * * *}")
    @Transactional
    public int raiseRenewalInvoices() {
        LocalDate today = LocalDate.now();
        int raised = 0;
        for (Client c : clients.findAll()) {
            if (c.getAnnualPrice() == null || c.isSuspended()) continue;
            if (c.getPaidUntil().isAfter(today.plusDays(RENEWAL_NOTICE_DAYS))) continue;
            if (invoices.existsByClient_IdAndStatus(c.getId(), Invoice.Status.OPEN)) continue;
            LocalDate due = c.getPaidUntil().isBefore(today) ? today : c.getPaidUntil();
            save(c, c.getAnnualPrice(), c.getCurrency(), c.getPaidUntil().plusYears(1), due, "Yearly renewal", "Automatic renewal");
            raised++;
        }
        if (raised > 0) log.info("Raised {} renewal invoice(s)", raised);
        return raised;
    }

    private Invoice save(Client c, BigDecimal amount, String currency, LocalDate coversUntil, LocalDate due, String notes, String actor) {
        Invoice i = new Invoice();
        i.setClient(c);
        i.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        i.setCurrency(currency == null || currency.isBlank() ? c.getCurrency() : currency.toUpperCase(Locale.ROOT));
        i.setCoversUntil(coversUntil);
        i.setDueDate(due);
        i.setNotes(notes == null || notes.isBlank() ? null : notes.trim());
        i.setPayToken(randomToken(32));
        i.setCreatedAt(LocalDateTime.now().withNano(0));
        i.setCreatedBy(actor);
        invoices.save(i);
        i.setNumber(String.format("INV-%d-%04d", i.getCreatedAt().getYear(), i.getId()));
        invoices.save(i);
        activity.record(c.getId(), actor, "Invoice raised", i.getNumber() + ": " + i.getCurrency() + " " + i.getAmount().toPlainString()
                + ", renews to " + coversUntil + ", due " + due);
        return i;
    }

    // ── Paying online ────────────────────────────────────────────────────

    /** Starts a Paystack checkout and returns the address to send the payer to. */
    @Transactional
    public String startCheckout(String payToken, String email) {
        Invoice i = byPayToken(payToken);
        if (i.getStatus() == Invoice.Status.PAID) throw new IllegalArgumentException("This invoice is already paid.");
        if (i.getStatus() == Invoice.Status.CANCELLED) throw new IllegalArgumentException("This invoice was cancelled.");
        String e = Objects.toString(email, "").trim();
        if (!e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || e.length() > 150) throw new IllegalArgumentException("Enter a valid email address for the receipt.");
        if (!paystack.isConfigured()) throw new IllegalArgumentException("Online payment isn't available yet. Please contact us to pay.");

        PaystackCheckout co = new PaystackCheckout();
        co.setInvoice(i);
        co.setReference(i.getNumber() + "-" + randomToken(6).replace("_", "x").replace("-", "y"));
        co.setAmountMinor(minor(i.getAmount()));
        co.setCurrency(i.getCurrency());
        co.setEmail(e);
        co.setCreatedAt(LocalDateTime.now().withNano(0));
        checkouts.save(co);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("invoice", i.getNumber());
        meta.put("institution", i.getClient().getCode());
        JsonNode data = paystack.initialize(e, co.getAmountMinor(), co.getCurrency(), co.getReference(),
                payUrl(i) + "/return", meta);
        String url = data.path("authorization_url").asText("");
        if (!url.startsWith("https://")) throw new PaystackClient.PaystackException("Paystack did not return a checkout page.");
        return url;
    }

    /** The outcome of confirming a payment with Paystack. */
    public enum Outcome { PAID, ALREADY_PAID, NOT_PAID, UNKNOWN_REFERENCE, MISMATCH }

    /**
     * Asks Paystack whether this reference was paid and, if it was (and for the right amount), records the
     * payment. Safe to call any number of times, from the return page and the webhook at once.
     */
    @Transactional
    public Outcome confirm(String reference, String source) {
        PaystackCheckout co = checkouts.findByReference(Objects.toString(reference, "")).orElse(null);
        if (co == null) return Outcome.UNKNOWN_REFERENCE;
        Invoice i = invoices.lockById(co.getInvoice().getId()).orElseThrow();
        if (co.getStatus() == PaystackCheckout.Status.SUCCESS) return Outcome.ALREADY_PAID;

        JsonNode data = paystack.verify(co.getReference());
        String status = data.path("status").asText("");
        if (!"success".equals(status)) {
            if ("failed".equals(status) || "abandoned".equals(status) || "reversed".equals(status)) {
                co.setStatus(PaystackCheckout.Status.FAILED);
                co.setMessage(cut(data.path("gateway_response").asText(status), 300));
                checkouts.save(co);
            }
            return Outcome.NOT_PAID;
        }
        if (data.path("amount").asLong(-1) != co.getAmountMinor() || !co.getCurrency().equalsIgnoreCase(data.path("currency").asText(""))) {
            co.setStatus(PaystackCheckout.Status.FAILED);
            co.setMessage("Paystack reports " + data.path("currency").asText() + " " + data.path("amount").asLong() + " (minor units); expected "
                    + co.getCurrency() + " " + co.getAmountMinor());
            checkouts.save(co);
            activity.record(i.getClient().getId(), source, "Payment amount mismatch", co.getReference() + ": " + co.getMessage());
            return Outcome.MISMATCH;
        }

        co.setStatus(PaystackCheckout.Status.SUCCESS);
        co.setChannel(cut(data.path("channel").asText(null), 30));
        co.setCompletedAt(LocalDateTime.now().withNano(0));
        checkouts.save(co);
        if (i.getStatus() == Invoice.Status.PAID) {
            // Paid twice (two checkouts both completed): keep the money on record, nothing more to renew
            activity.record(i.getClient().getId(), source, "Second payment for a paid invoice", co.getReference() + ". Refund or credit it.");
            return Outcome.ALREADY_PAID;
        }

        PaymentForm p = new PaymentForm();
        p.setAmount(i.getAmount());
        p.setCurrency(i.getCurrency());
        p.setPaidOn(paidOn(data));
        p.setCoversUntil(i.getCoversUntil().isBefore(p.getPaidOn()) ? p.getPaidOn() : i.getCoversUntil());
        p.setMethod(cut("Paystack" + (co.getChannel() == null ? "" : " (" + co.getChannel().replace('_', ' ') + ")"), 30));
        p.setReference(co.getReference());
        p.setNotes("Invoice " + i.getNumber() + (i.getStatus() == Invoice.Status.CANCELLED ? " (paid after it was cancelled)" : ""));
        Payment payment = clientService.recordPayment(i.getClient().getId(), p, PAYSTACK_ACTOR);
        i.setStatus(Invoice.Status.PAID);
        i.setPaidAt(LocalDateTime.now().withNano(0));
        i.setPaidReference(co.getReference());
        invoices.save(i);
        activity.record(i.getClient().getId(), source, "Invoice paid online", i.getNumber() + " · " + payment.getCurrency() + " "
                + payment.getAmount().toPlainString() + " · " + co.getReference());
        return Outcome.PAID;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    static long minor(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static LocalDate paidOn(JsonNode data) {
        String at = data.path("paid_at").asText(data.path("paidAt").asText(""));
        try { return OffsetDateTime.parse(at).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate(); }
        catch (Exception e) { return LocalDate.now(); }
    }

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String cut(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max); }
}
