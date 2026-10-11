package com.vendor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class KeySignerTest {

    public static boolean verify(String key, PublicKey pub) throws Exception {
        String[] p = key.split("\\.");
        Signature s = Signature.getInstance("Ed25519");
        s.initVerify(pub);
        s.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
        return s.verify(Base64.getUrlDecoder().decode(p[2]));
    }

    public static JsonNode payload(String key) throws Exception {
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(key.split("\\.")[1]));
    }

    @Test
    void signsInTheExamPortalsFormat() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeySigner signer = new KeySigner(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        String key = signer.sign(new KeySigner.Terms("id-1", "UCC", "University of Cape Coast", "standard",
                LocalDate.of(2026, 10, 11), LocalDate.of(2027, 1, 31), 14, 7, 1234L, false, null));

        assertThat(key).startsWith("EPSUB1.");
        assertThat(verify(key, pair.getPublic())).isTrue();
        JsonNode n = payload(key);
        assertThat(n.path("v").asInt()).isEqualTo(1);
        assertThat(n.path("inst").asText()).isEqualTo("UCC");
        assertThat(n.path("paidUntil").asText()).isEqualTo("2027-01-31");
        assertThat(n.path("graceDays").asInt()).isEqualTo(14);
        assertThat(n.path("seq").asLong()).isEqualTo(1234L);
        assertThat(n.has("suspended")).isFalse();
    }

    @Test
    void aSuspendingKeyCarriesItsReason() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeySigner signer = new KeySigner(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        JsonNode n = payload(signer.sign(new KeySigner.Terms("id-2", "UCC", "U", "standard", LocalDate.now(),
                LocalDate.now(), 14, 7, 1L, true, "Contract ended")));
        assertThat(n.path("suspended").asBoolean()).isTrue();
        assertThat(n.path("note").asText()).isEqualTo("Contract ended");
    }

    @Test
    void withoutAPrivateKeyNothingIsSigned() {
        KeySigner signer = new KeySigner("");
        assertThat(signer.available()).isFalse();
        assertThatThrownBy(() -> signer.sign(new KeySigner.Terms("x", "U", "U", "p", LocalDate.now(), LocalDate.now(), 0, 0, 1, false, null)))
                .hasMessageContaining("SIGNING_PRIVATE_KEY");
        assertThatThrownBy(() -> new KeySigner("not-a-key")).hasMessageContaining("SIGNING_PRIVATE_KEY");
    }
}
