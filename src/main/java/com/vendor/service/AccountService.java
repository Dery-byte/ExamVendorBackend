package com.vendor.service;

import com.vendor.model.VendorUser;
import com.vendor.repository.VendorUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who can sign in, and how: like the exam portal's developer sign-in. Only the emails in the vendor_user
 * table (edited directly in the database) can sign in, with a 6-digit code emailed to them: valid 10
 * minutes, 5 tries, a new one at most once a minute. There are no passwords.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final long CODE_TTL_SECONDS = 10 * 60;
    private static final long RESEND_AFTER_SECONDS = 60;
    public static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Pending(byte[] hash, Instant expires, Instant sentAt, int attempts) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final VendorUserRepository users;
    private final ActivityLog activity;
    private final Mailer mailer;

    /** Local development: also print codes to the log. Never in production. */
    @Value("${app.login.log-codes:false}") private boolean logCodes;
    @Value("${app.admin.email:}") private String adminEmail;

    public AccountService(VendorUserRepository users, ActivityLog activity, Mailer mailer) {
        this.users = users;
        this.activity = activity;
        this.mailer = mailer;
    }

    /**
     * The address(es) in VENDOR_ADMIN_EMAIL (comma-separated) can always sign in: each is added if missing.
     * Everyone else is added or removed in the vendor_user table.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureAdminAccounts() {
        for (String raw : Objects.toString(adminEmail, "").split("[,;\\s]+")) {
            String email = normalise(raw);
            if (email.isEmpty()) continue;
            if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                log.warn("VENDOR_ADMIN_EMAIL: '{}' is not an email address; ignored.", raw);
                continue;
            }
            if (users.findByEmail(email).isPresent()) continue;
            VendorUser u = new VendorUser();
            u.setEmail(email);
            u.setName("Administrator");
            u.setCreatedAt(LocalDateTime.now().withNano(0));
            users.save(u);
            activity.record(null, "System", "Account added from VENDOR_ADMIN_EMAIL", email);
            log.info("[VENDOR-LOGIN] Added {} (VENDOR_ADMIN_EMAIL); it signs in with an emailed code.", email);
        }
        if (users.count() == 0)
            log.warn("[VENDOR-LOGIN] Nobody can sign in yet: set VENDOR_ADMIN_EMAIL, or add a row to the vendor_user table.");
    }

    public boolean emailConfigured() { return mailer.configured(); }

    public String fromAddress() { return mailer.fromAddress(); }

    /**
     * Sends a code if the email is in vendor_user. The page always shows the same reply; what really happened
     * is in the server log ([VENDOR-LOGIN]).
     */
    public void requestCode(String rawEmail) {
        String email = normalise(rawEmail);
        if (email.isEmpty()) return;
        if (users.findByEmail(email).isEmpty()) {
            log.info("[VENDOR-LOGIN] Code requested for {}, which is not in vendor_user: nothing sent.", email);
            return;
        }
        Pending prev = pending.get(email);
        if (prev != null && prev.sentAt().plusSeconds(RESEND_AFTER_SECONDS).isAfter(Instant.now())) {
            log.info("[VENDOR-LOGIN] Code for {} was sent less than a minute ago: not sent again yet.", email);
            return;
        }
        String code = issue(email);
        if (logCodes) log.warn("[VENDOR-LOGIN] Code for {}: {} (VENDOR_LOG_CODES=true; local use only)", email, code);
        CompletableFuture.runAsync(() -> sendCode(email, code));
    }

    /** Creates and remembers a new code for an approved email. Public for tests. */
    public String issue(String email) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        pending.put(normalise(email), new Pending(hash(code), Instant.now().plusSeconds(CODE_TTL_SECONDS), Instant.now(), 0));
        return code;
    }

    /** The account when the code is right; otherwise an IllegalArgumentException with one generic message. */
    public VendorUser verify(String rawEmail, String rawCode) {
        String email = normalise(rawEmail);
        String code = rawCode == null ? "" : rawCode.replaceAll("\\s", "");
        IllegalArgumentException wrong = new IllegalArgumentException("That code is wrong or has expired. Request a new one.");
        Pending p = pending.get(email);
        Optional<VendorUser> user = users.findByEmail(email);
        if (p == null || user.isEmpty()) throw wrong;
        if (Instant.now().isAfter(p.expires()) || p.attempts() >= MAX_ATTEMPTS) {
            pending.remove(email);
            throw wrong;
        }
        if (!MessageDigest.isEqual(p.hash(), hash(code))) {
            pending.put(email, new Pending(p.hash(), p.expires(), p.sentAt(), p.attempts() + 1));
            throw wrong;
        }
        pending.remove(email);
        VendorUser u = user.get();
        u.setLastLoginAt(LocalDateTime.now().withNano(0));
        users.save(u);
        activity.record(null, email, "Signed in", null);
        return u;
    }

    public VendorUser byEmail(String email) {
        return users.findByEmail(normalise(email)).orElseThrow();
    }

    /** Everyone who can sign in, for the account page (read-only: the table is edited in the database). */
    public List<VendorUser> team() {
        List<VendorUser> list = new ArrayList<>(users.findAll());
        list.sort(Comparator.comparing(VendorUser::getEmail));
        return list;
    }

    private void sendCode(String email, String code) {
        Mailer.Result r = mailer.send(email, "Your vendor portal sign-in code: " + code,
                "Your vendor portal sign-in code is " + code + ".\n\nIt expires in 10 minutes. If you didn't ask for it, ignore this email.",
                "<p>Your vendor portal sign-in code is</p>"
                        + "<p style=\"font-size:26px;font-weight:bold;letter-spacing:4px\">" + code + "</p>"
                        + "<p>It expires in 10 minutes. If you didn't ask for it, ignore this email.</p>",
                "vendor-sign-in");
        if (r.sent()) log.info("[VENDOR-LOGIN] Code emailed to {} (Mailjet message {}).", email, r.messageId());
        else log.error("[VENDOR-LOGIN] Code NOT emailed to {}: {}", email, r.error());
    }

    /** "Send me a test email" on the account page: sends now and says exactly what Mailjet did. */
    public Mailer.Result sendTestEmail(String email) {
        Mailer.Result r = mailer.send(normalise(email), "Vendor portal test email",
                "This is a test email from the vendor portal. Sign-in codes come from the same address.",
                "<p>This is a test email from the vendor portal.</p><p>Sign-in codes come from the same address.</p>",
                "vendor-test");
        log.info("[MAIL] Test email to {}: {}", email, r.sent() ? "sent (Mailjet message " + r.messageId() + ")" : r.error());
        return r;
    }

    public static String normalise(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }

    private static byte[] hash(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
