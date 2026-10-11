package com.vendor.service;

import com.vendor.model.VendorUser;
import com.vendor.repository.VendorUserRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
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

    @Autowired(required = false) private JavaMailSender mailSender;
    @Value("${spring.mail.username:}") private String mailUsername;
    @Value("${app.mail.from-address:optimusinforservice@gmail.com}") private String fromAddress;
    @Value("${app.mail.from-name:Vendor portal}") private String fromName;
    /** Local development without email: print codes to the log. Never in production. */
    @Value("${app.login.log-codes:false}") private boolean logCodes;
    @Value("${app.admin.email:}") private String adminEmail;

    public AccountService(VendorUserRepository users, ActivityLog activity) {
        this.users = users;
        this.activity = activity;
    }

    /** On a new database, the address in VENDOR_ADMIN_EMAIL becomes the first account. */
    @EventListener(ApplicationReadyEvent.class)
    public void createFirstAccount() {
        if (users.count() > 0) return;
        String email = normalise(adminEmail);
        if (!email.matches("[^@\\s]+@[^@\\s]+")) {
            log.warn("No vendor accounts yet. Set VENDOR_ADMIN_EMAIL and restart, or add a row to the vendor_user table.");
            return;
        }
        VendorUser u = new VendorUser();
        u.setEmail(email);
        u.setName("Administrator");
        u.setCreatedAt(LocalDateTime.now().withNano(0));
        users.save(u);
        activity.record(null, "System", "First account added", email);
        log.info("Added {} as the first vendor account; it signs in with an emailed code.", email);
    }

    public boolean emailConfigured() { return mailSender != null && mailUsername != null && !mailUsername.isBlank(); }

    /** Sends a code if the email is in vendor_user. The caller always shows the same reply either way. */
    public void requestCode(String rawEmail) {
        String email = normalise(rawEmail);
        if (email.isEmpty() || users.findByEmail(email).isEmpty()) return;
        Pending prev = pending.get(email);
        if (prev != null && prev.sentAt().plusSeconds(RESEND_AFTER_SECONDS).isAfter(Instant.now())) return;
        String code = issue(email);
        if (logCodes) log.warn("[VENDOR-LOGIN] Code for {}: {} (VENDOR_LOG_CODES=true; local use only)", email, code);
        if (emailConfigured()) CompletableFuture.runAsync(() -> sendCode(email, code));
        else if (!logCodes) log.error("[VENDOR-LOGIN] Email is not set up (MAILJET_SMTP_USERNAME / PASSWORD): the code for {} can't be sent.", email);
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
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(email);
            helper.setSubject("Your vendor portal sign-in code: " + code);
            helper.setText("<p>Your vendor portal sign-in code is</p>"
                    + "<p style=\"font-size:26px;font-weight:bold;letter-spacing:4px\">" + code + "</p>"
                    + "<p>It expires in 10 minutes. If you didn't ask for it, ignore this email.</p>", true);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("[VENDOR-LOGIN] Could not send the code to {}: {}", email, e.getMessage());
        }
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
