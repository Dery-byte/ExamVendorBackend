package com.vendor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.vendor.model.Client;
import com.vendor.model.Invoice;
import com.vendor.model.IssuedKey;
import com.vendor.repository.ClientRepository;
import com.vendor.repository.InvoiceRepository;
import com.vendor.repository.IssuedKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A client's exam portal checking in: records what it reports and hands back its newest key.
 * The portal decides for itself whether to install it (only if it is newer and correctly signed).
 */
@Service
public class CheckInService {

    /** How often portals are asked to check in. */
    public static final int CHECK_IN_HOURS = 6;

    private final ClientRepository clients;
    private final IssuedKeyRepository keys;
    private final ActivityLog activity;
    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;

    public CheckInService(ClientRepository clients, IssuedKeyRepository keys, ActivityLog activity,
                          InvoiceRepository invoices, InvoiceService invoiceService) {
        this.clients = clients;
        this.keys = keys;
        this.activity = activity;
        this.invoices = invoices;
        this.invoiceService = invoiceService;
    }

    public Optional<Client> authenticate(String bearerToken) {
        if (bearerToken == null || !bearerToken.startsWith("vpt_") || bearerToken.length() > 200) return Optional.empty();
        return clients.findByTokenHash(ClientService.sha256(bearerToken));
    }

    @Transactional
    public Map<String, Object> checkIn(Client c, JsonNode body, String ip) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        boolean first = c.getLastCheckInAt() == null;
        String state = text(body, "state", 20);
        String previousState = c.getReportedState();
        String keyId = text(body, "installedKeyId", 64);

        c.setLastCheckInAt(now);
        c.setLastCheckInIp(cut(ip, 64));
        c.setReportedState(state);
        c.setReportedVersion(text(body, "appVersion", 40));
        c.setReportedKeyId(keyId);
        c.setReportedKeyProblem(text(body, "keyProblem", 300));
        c.setReportedPortalUrl(text(body, "portalUrl", 300));
        c.setReportedStudents(body.hasNonNull("students") ? body.get("students").asLong() : null);
        c.setReportedStaff(body.hasNonNull("staff") ? body.get("staff").asLong() : null);
        clients.save(c);

        if (first) activity.record(c.getId(), "Client portal", "First check-in", "From " + ip);
        else if (state != null && !Objects.equals(state, previousState))
            activity.record(c.getId(), "Client portal", "Portal state changed", previousState + " → " + state);
        if (keyId != null) keys.findByClient_IdAndKeyId(c.getId(), keyId).ifPresent(k -> {
            if (k.getDeliveredAt() == null) {
                k.setDeliveredAt(now);
                keys.save(k);
                activity.record(c.getId(), "Client portal", "Key in use", k.getReason());
            }
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", keys.findFirstByClient_IdOrderBySeqDesc(c.getId()).map(IssuedKey::getRaw).orElse(null));
        out.put("invoice", invoices.findFirstByClient_IdAndStatusOrderByCreatedAtDescIdDesc(c.getId(), Invoice.Status.OPEN)
                .map(this::invoiceMap).orElse(null));
        out.put("checkInHours", CHECK_IN_HOURS);
        out.put("serverTime", now.toString());
        return out;
    }

    private Map<String, Object> invoiceMap(Invoice i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", i.getNumber());
        m.put("amount", i.getAmount().toPlainString());
        m.put("currency", i.getCurrency());
        m.put("dueDate", i.getDueDate().toString());
        m.put("coversUntil", i.getCoversUntil().toString());
        m.put("payUrl", invoiceService.payUrl(i));
        return m;
    }

    private static String text(JsonNode n, String field, int max) {
        if (n == null || !n.hasNonNull(field)) return null;
        String v = n.get(field).asText("").trim();
        return v.isEmpty() ? null : cut(v, max);
    }

    private static String cut(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max); }
}
