package com.vendor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Sends email through Mailjet's Send API (v3.1) over HTTPS, with the same API key / secret the exam
 * portal uses for SMTP. HTTPS works on any host (some block the SMTP ports) and Mailjet answers each
 * message with an ID or the exact reason it was refused, which is logged.
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String apiKey;
    private final String apiSecret;
    private final String baseUrl;
    private final String fromAddress;
    private final String fromName;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Mailer(@Value("${app.mailjet.api-key:}") String apiKey,
                  @Value("${app.mailjet.api-secret:}") String apiSecret,
                  @Value("${app.mailjet.base-url:https://api.mailjet.com}") String baseUrl,
                  @Value("${app.mail.from-address:optimusinforservice@gmail.com}") String fromAddress,
                  @Value("${app.mail.from-name:Vendor portal}") String fromName) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.apiSecret = apiSecret == null ? "" : apiSecret.trim();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.fromAddress = fromAddress == null ? "" : fromAddress.trim();
        this.fromName = fromName;
    }

    /** What happened to one email. */
    public record Result(boolean sent, String messageId, String error) {
        static Result ok(String id) { return new Result(true, id, null); }
        static Result failed(String why) { return new Result(false, null, why); }
    }

    public boolean configured() { return !apiKey.isEmpty() && !apiSecret.isEmpty(); }

    public String fromAddress() { return fromAddress; }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        if (configured()) log.info("[MAIL] Sending through Mailjet's API as {} <{}>", fromName, fromAddress);
        else log.warn("[MAIL] Mailjet is not set up (MAILJET_SMTP_USERNAME / MAILJET_SMTP_PASSWORD): no email can be sent.");
    }

    /** Network failures are retried after these pauses (a dropped connection, a slow DNS answer …). */
    private static final long[] RETRY_PAUSES_MS = {2_000, 5_000};

    /**
     * Sends one email now and says what Mailjet did with it. A failed connection is tried again up to
     * twice; a refusal from Mailjet (bad key, unverified sender …) is not, as trying again can't help. Never throws.
     */
    public Result send(String to, String subject, String text, String html, String customId) {
        if (!configured()) return Result.failed("Mailjet is not set up on this server (MAILJET_SMTP_USERNAME / MAILJET_SMTP_PASSWORD).");
        String body;
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("From", Map.of("Email", fromAddress, "Name", fromName));
            message.put("To", List.of(Map.of("Email", to)));
            message.put("Subject", subject);
            message.put("TextPart", text);
            message.put("HTMLPart", html);
            if (customId != null) message.put("CustomID", customId);
            body = JSON.writeValueAsString(Map.of("Messages", List.of(message)));
        } catch (Exception e) {
            return Result.failed("Could not build the email: " + e.getMessage());
        }
        String auth = Base64.getEncoder().encodeToString((apiKey + ":" + apiSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v3.1/send"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> res;
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                if (attempt < RETRY_PAUSES_MS.length) {
                    log.warn("[MAIL] Could not reach Mailjet ({}); trying again in {} s.", e, RETRY_PAUSES_MS[attempt] / 1000);
                    if (!pause(RETRY_PAUSES_MS[attempt])) return Result.failed("Interrupted.");
                    continue;
                }
                return Result.failed("Could not reach Mailjet after " + (attempt + 1) + " tries (check this server's internet connection): " + e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.failed("Interrupted.");
            }
            return read(res);
        }
    }

    private Result read(HttpResponse<String> res) {
        if (res.statusCode() == 401) return Result.failed("Mailjet refused the API key / secret (check MAILJET_SMTP_USERNAME and MAILJET_SMTP_PASSWORD).");
        try {
            JsonNode m = JSON.readTree(res.body()).path("Messages").path(0);
            if ("success".equals(m.path("Status").asText()))
                return Result.ok(m.path("To").path(0).path("MessageID").asText(""));
            JsonNode err = m.path("Errors").path(0);
            String why = err.isMissingNode() ? "HTTP " + res.statusCode() + ": " + cut(res.body())
                    : err.path("ErrorMessage").asText("refused") + (err.has("ErrorRelatedTo") ? " (" + err.path("ErrorRelatedTo") + ")" : "");
            return Result.failed("Mailjet refused the email: " + why);
        } catch (Exception e) {
            return Result.failed("Mailjet answered HTTP " + res.statusCode() + " with something unreadable: " + cut(res.body()));
        }
    }

    private static boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String cut(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s; }
}
