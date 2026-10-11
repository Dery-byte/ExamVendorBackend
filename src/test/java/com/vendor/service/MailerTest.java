package com.vendor.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Sending through a fake Mailjet Send API: what was sent, and how refusals are reported. */
class MailerTest {

    private HttpServer server;
    private int status = 200;
    /** How many requests to drop (close without answering) before replying normally. */
    private int dropFirst = 0;
    private String answer;
    private String gotAuth, gotBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3.1/send", ex -> {
            if (dropFirst > 0) {
                dropFirst--;
                ex.getRequestBody().readAllBytes();
                ex.close();   // no answer: the client sees a broken connection
                return;
            }
            gotAuth = ex.getRequestHeaders().getFirst("Authorization");
            gotBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private Mailer mailer(String key) {
        return new Mailer(key, "secret", "http://127.0.0.1:" + server.getAddress().getPort(), "codes@example.com", "Vendor portal");
    }

    @Test
    void sendsThroughTheApiAndReportsTheMessageId() {
        answer = "{\"Messages\":[{\"Status\":\"success\",\"To\":[{\"Email\":\"me@example.com\",\"MessageID\":1152921512345}]}]}";
        Mailer.Result r = mailer("key").send("me@example.com", "Your code: 123456", "text 123456", "<p>123456</p>", "vendor-sign-in");
        assertThat(r.sent()).isTrue();
        assertThat(r.messageId()).isEqualTo("1152921512345");
        assertThat(gotAuth).isEqualTo("Basic a2V5OnNlY3JldA==");   // key:secret
        assertThat(gotBody).contains("\"Email\":\"codes@example.com\"", "\"TextPart\":\"text 123456\"", "\"HTMLPart\"", "me@example.com");
    }

    @Test
    void aDroppedConnectionIsTriedAgain() {
        dropFirst = 1;
        answer = "{\"Messages\":[{\"Status\":\"success\",\"To\":[{\"Email\":\"me@example.com\",\"MessageID\":42}]}]}";
        Mailer.Result r = mailer("key").send("me@example.com", "s", "t", "h", null);
        assertThat(r.sent()).isTrue();
        assertThat(r.messageId()).isEqualTo("42");
    }

    @Test
    void anUnreachableMailjetIsReportedAfterThreeTries() {
        Mailer m = new Mailer("key", "secret", "http://127.0.0.1:1", "codes@example.com", "Vendor portal");
        assertThat(m.send("me@example.com", "s", "t", "h", null).error()).contains("after 3 tries");
    }

    @Test
    void explainsWhyMailjetRefused() {
        status = 400;
        answer = "{\"Messages\":[{\"Status\":\"error\",\"Errors\":[{\"ErrorMessage\":\"The sender is not authorized\",\"ErrorRelatedTo\":[\"From.Email\"]}]}]}";
        Mailer.Result r = mailer("key").send("me@example.com", "s", "t", "h", null);
        assertThat(r.sent()).isFalse();
        assertThat(r.error()).contains("The sender is not authorized", "From.Email");

        status = 401;
        answer = "{\"ErrorMessage\":\"API key authentication/authorization failure\"}";
        assertThat(mailer("key").send("me@example.com", "s", "t", "h", null).error()).contains("API key / secret");
    }

    @Test
    void saysWhenMailjetIsNotSetUp() {
        Mailer m = mailer("");
        assertThat(m.configured()).isFalse();
        assertThat(m.send("me@example.com", "s", "t", "h", null).error()).contains("not set up");
    }
}
