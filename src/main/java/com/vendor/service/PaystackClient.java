package com.vendor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paystack Transactions API (https://paystack.com/docs/api/transaction), as the exam portal's fees use it.
 * The secret key comes from PAYSTACK_SECRET_KEY and never leaves the server.
 */
@Component
public class PaystackClient {

    private static final Logger log = LoggerFactory.getLogger(PaystackClient.class);
    /** Card, Mobile Money and bank transfer. */
    private static final List<String> CHANNELS = List.of("card", "mobile_money", "bank_transfer");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String secretKey;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public PaystackClient(@Value("${app.paystack.secret-key:}") String secretKey,
                          @Value("${app.paystack.base-url:https://api.paystack.co}") String baseUrl) {
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public boolean isConfigured() { return secretKey.startsWith("sk_"); }

    /** "live", "test" or null. */
    public String mode() {
        if (!isConfigured()) return null;
        return secretKey.startsWith("sk_live_") ? "live" : "test";
    }

    /** Starts a checkout; Paystack's {@code data} has authorization_url, access_code, reference. */
    public JsonNode initialize(String email, long amountMinor, String currency, String reference, String callbackUrl,
                               Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("amount", amountMinor);
        body.put("currency", currency);
        body.put("reference", reference);
        body.put("callback_url", callbackUrl);
        body.put("channels", CHANNELS);
        body.put("metadata", metadata);
        return call("POST", "/transaction/initialize", body);
    }

    /** Paystack's {@code data} for a transaction: status, amount (minor units), currency, channel, paid_at … */
    public JsonNode verify(String reference) {
        return call("GET", "/transaction/verify/" + URLEncoder.encode(reference, StandardCharsets.UTF_8), null);
    }

    /** True when x-paystack-signature is the HMAC-SHA512 of the raw body with the secret key. */
    public boolean validSignature(byte[] rawBody, String signature) {
        if (!isConfigured() || signature == null || signature.isBlank() || rawBody == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(rawBody)).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[Paystack] Could not check a webhook signature", e);
            return false;
        }
    }

    private JsonNode call(String method, String path, Object body) {
        if (!isConfigured()) throw new PaystackException("Online payment is not set up yet.");
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Accept", "application/json");
            if (body != null) req.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            else req.method(method, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode root;
            try { root = JSON.readTree(res.body()); }
            catch (Exception e) { throw new PaystackException("Paystack returned an unexpected response."); }
            if (res.statusCode() / 100 != 2 || !root.path("status").asBoolean(false)) {
                String message = root.path("message").asText("Paystack refused the request.");
                log.warn("[Paystack] {} {} -> {} {}", method, path, res.statusCode(), message);
                throw new PaystackException(message);
            }
            return root.path("data");
        } catch (PaystackException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaystackException("Interrupted.");
        } catch (Exception e) {
            log.warn("[Paystack] {} {} failed: {}", method, path, e.toString());
            throw new PaystackException("Could not reach Paystack. Please try again in a moment.");
        }
    }

    public static class PaystackException extends RuntimeException {
        public PaystackException(String message) { super(message); }
    }
}
