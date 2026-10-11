package com.vendor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;

/**
 * Signs subscription keys in the exam portal's format: {@code EPSUB1.<base64url JSON>.<base64url Ed25519>},
 * the same as subscription-tool/SubscriptionKeyTool.java. The private key comes from SIGNING_PRIVATE_KEY.
 */
@Component
public class KeySigner {

    private static final Logger log = LoggerFactory.getLogger(KeySigner.class);
    public static final String PREFIX = "EPSUB1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PrivateKey key;

    public KeySigner(String privateKeyB64) {
        this(privateKeyB64, "");
    }

    /** The key itself (SIGNING_PRIVATE_KEY) or, for local runs, the file holding it (SIGNING_PRIVATE_KEY_FILE). */
    @org.springframework.beans.factory.annotation.Autowired
    public KeySigner(@Value("${app.signing.private-key:}") String privateKeyB64,
                     @Value("${app.signing.private-key-file:}") String privateKeyFile) {
        this.key = decode(privateKeyB64 != null && !privateKeyB64.isBlank() ? privateKeyB64 : readFile(privateKeyFile));
        if (key == null) log.warn("SIGNING_PRIVATE_KEY is not set: keys can't be issued until it is.");
    }

    private static String readFile(String path) {
        if (path == null || path.isBlank()) return null;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(path.trim()))) {
            log.warn("SIGNING_PRIVATE_KEY_FILE: no file at {}", java.nio.file.Path.of(path.trim()).toAbsolutePath());
            return null;
        }
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path.trim())).trim();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("SIGNING_PRIVATE_KEY_FILE: can't read " + java.nio.file.Path.of(path.trim()).toAbsolutePath(), e);
        }
    }

    public boolean available() { return key != null; }

    /** What goes into a key. */
    public record Terms(String id, String institutionId, String institutionName, String plan, LocalDate issued,
                        LocalDate paidUntil, int graceDays, int restrictedDays, long seq, boolean suspended, String note) {}

    public String sign(Terms t) {
        if (key == null) throw new IllegalStateException("Keys can't be issued: SIGNING_PRIVATE_KEY is not set on this server.");
        ObjectNode n = JSON.createObjectNode();
        n.put("v", 1);
        n.put("id", t.id());
        n.put("inst", t.institutionId());
        n.put("name", t.institutionName());
        n.put("plan", t.plan());
        n.put("issued", t.issued().toString());
        n.put("paidUntil", t.paidUntil().toString());
        n.put("graceDays", t.graceDays());
        n.put("restrictedDays", t.restrictedDays());
        n.put("seq", t.seq());
        if (t.suspended()) {
            n.put("suspended", true);
            if (t.note() != null) n.put("note", t.note());
        }
        try {
            String signed = PREFIX + "." + b64(JSON.writeValueAsBytes(n));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(signed.getBytes(StandardCharsets.US_ASCII));
            return signed + "." + b64(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the key", e);
        }
    }

    /** Base64 of the key, forgiving how it was pasted: quotes, PEM header lines, spaces or line breaks. */
    private static PrivateKey decode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String b64 = raw.replaceAll("-----(BEGIN|END)[A-Z ]*-----", "").replaceAll("[\\s\"']+", "");
        if (b64.isEmpty()) return null;
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            throw new IllegalStateException("SIGNING_PRIVATE_KEY is not a valid key: paste the one line from "
                    + "subscription-tool/keys-online/private.key (it starts with MC4CAQAw)", e);
        }
    }

    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
