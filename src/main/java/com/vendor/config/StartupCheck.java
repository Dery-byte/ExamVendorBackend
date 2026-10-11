package com.vendor.config;

import com.vendor.service.KeySigner;
import com.vendor.service.Mailer;
import com.vendor.service.PaystackClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Says at start-up which settings were found (names and status only, never values), so a missing or
 * misspelt environment variable on the server shows up in the first lines of the log. On the server
 * (prod) it stops with one plain message when the portal can't work without a setting.
 */
@Component
public class StartupCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupCheck.class);

    private final Environment env;
    private final KeySigner signer;
    private final Mailer mailer;
    private final PaystackClient paystack;

    @Value("${app.public-url:}") private String publicUrl;
    @Value("${app.admin.email:}") private String adminEmail;

    public StartupCheck(Environment env, KeySigner signer, Mailer mailer, PaystackClient paystack) {
        this.env = env;
        this.signer = signer;
        this.mailer = mailer;
        this.paystack = paystack;
    }

    @PostConstruct
    void check() {
        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
        String source = env.getProperty("PUBLIC_URL", "").isBlank() && !env.getProperty("RENDER_EXTERNAL_URL", "").isBlank()
                ? " (from RENDER_EXTERNAL_URL)" : "";
        log.info("[CONFIG] Profile: {}", String.join(",", env.getActiveProfiles()));
        log.info("[CONFIG] Public address: {}{}", publicUrl.isBlank() ? "MISSING (PUBLIC_URL)" : publicUrl, source);
        log.info("[CONFIG] Signing key: {}", signer.available() ? "loaded" : "MISSING (SIGNING_PRIVATE_KEY): keys can't be issued");
        log.info("[CONFIG] Email: {}", mailer.configured() ? "Mailjet, from " + mailer.fromAddress()
                : "MISSING (MAILJET_SMTP_USERNAME / MAILJET_SMTP_PASSWORD): sign-in codes can't be sent");
        log.info("[CONFIG] Sign-in addresses from VENDOR_ADMIN_EMAIL: {}", adminEmail.isBlank() ? "none" : adminEmail);
        log.info("[CONFIG] Paystack: {}", paystack.mode() == null ? "off (PAYSTACK_SECRET_KEY)" : paystack.mode() + " mode");

        if (!prod) return;
        List<String> missing = new ArrayList<>();
        if (publicUrl.isBlank()) missing.add("PUBLIC_URL (this portal's https address)");
        if (!mailer.configured()) missing.add("MAILJET_SMTP_USERNAME and MAILJET_SMTP_PASSWORD (nobody could sign in)");
        if (!missing.isEmpty())
            throw new IllegalStateException("The vendor portal can't start: set " + String.join("; ", missing)
                    + " in the server's environment variables, then redeploy.");
        if (!publicUrl.startsWith("https://")) log.warn("[CONFIG] PUBLIC_URL should start with https:// on the server.");
        if (!signer.available()) log.error("[CONFIG] SIGNING_PRIVATE_KEY is missing: the portal runs, but can't issue or renew any key.");
    }
}
