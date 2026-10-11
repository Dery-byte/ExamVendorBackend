package com.vendor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vendor.model.Client;
import com.vendor.model.Invoice;
import com.vendor.repository.*;
import com.vendor.service.InvoiceService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.vendor.service.KeySignerTest.payload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Invoices and paying them through a fake Paystack: the return page, the webhook, and what can go wrong. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:billing;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.mailjet.api-key=", "app.mailjet.api-secret=", "app.login.log-codes=false",
        "app.paystack.secret-key=sk_test_billing",
        "app.public-url=https://vendor.example.com",
        "app.billing.renewal-cron=-",
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")   // whatever application.properties says
class BillingIntegrationTest {

    static final String SECRET = "sk_test_billing";
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpServer PAYSTACK;
    /** reference -> [amount (minor), currency] as initialised; what verify reports back. */
    static final Map<String, Object[]> CHECKOUTS = new ConcurrentHashMap<>();
    static volatile String verifyStatus = "success";
    static volatile long amountDelta = 0;

    static {
        try {
            PAYSTACK = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PAYSTACK.createContext("/transaction/initialize", ex -> {
                JsonNode b = JSON.readTree(ex.getRequestBody());
                CHECKOUTS.put(b.path("reference").asText(), new Object[]{b.path("amount").asLong(), b.path("currency").asText()});
                reply(ex, "{\"status\":true,\"data\":{\"authorization_url\":\"https://checkout.paystack.com/fake123\",\"reference\":\""
                        + b.path("reference").asText() + "\"}}");
            });
            PAYSTACK.createContext("/transaction/verify/", ex -> {
                String ref = ex.getRequestURI().getPath().substring("/transaction/verify/".length());
                Object[] c = CHECKOUTS.get(ref);
                reply(ex, c == null ? "{\"status\":false,\"message\":\"Transaction reference not found\"}"
                        : "{\"status\":true,\"data\":{\"status\":\"" + verifyStatus + "\",\"reference\":\"" + ref + "\",\"amount\":"
                        + ((long) c[0] + amountDelta) + ",\"currency\":\"" + c[1] + "\",\"channel\":\"mobile_money\",\"paid_at\":\"2026-10-11T10:00:00.000Z\"}}");
            });
            PAYSTACK.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    @AfterAll
    static void stop() { PAYSTACK.stop(0); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String key = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded());
        r.add("app.signing.private-key", () -> key);
        r.add("app.paystack.base-url", () -> "http://127.0.0.1:" + PAYSTACK.getAddress().getPort());
    }

    @Autowired MockMvc mvc;
    @Autowired ClientRepository clients;
    @Autowired IssuedKeyRepository keys;
    @Autowired PaymentRepository payments;
    @Autowired InvoiceRepository invoices;
    @Autowired PaystackCheckoutRepository checkouts;
    @Autowired InvoiceService invoiceService;

    @BeforeEach
    void clean() {
        checkouts.deleteAll();
        invoices.deleteAll();
        payments.deleteAll();
        keys.deleteAll();
        clients.deleteAll();
        CHECKOUTS.clear();
        verifyStatus = "success";
        amountDelta = 0;
    }

    private static RequestPostProcessor admin() { return user("admin@vendor.test").roles("VENDOR"); }

    /** A client paid until the given day, with a yearly price; returns [id, check-in token]. */
    private Object[] client(LocalDate paidUntil, String price) throws Exception {
        MvcResult r = mvc.perform(post("/clients").with(admin()).with(csrf())
                .param("code", "UCC").param("name", "University of Cape Coast").param("plan", "standard")
                .param("paidUntil", paidUntil.toString()).param("graceDays", "14").param("restrictedDays", "7")
                .param("annualPrice", price).param("currency", "GHS").param("billingEmail", "bursar@ucc.edu.gh")).andReturn();
        Client c = clients.findAll().get(0);
        return new Object[]{c.getId(), r.getFlashMap().get("newToken")};
    }

    private Invoice raise(Object[] c, String amount, LocalDate coversUntil) throws Exception {
        mvc.perform(post("/clients/" + c[0] + "/invoices").with(admin()).with(csrf())
                .param("amount", amount).param("currency", "GHS").param("coversUntil", coversUntil.toString())
                .param("dueDate", LocalDate.now().toString()));
        return invoices.findAll().stream().filter(i -> i.getStatus() == Invoice.Status.OPEN).findFirst().orElseThrow();
    }

    private JsonNode checkIn(Object[] c) throws Exception {
        return JSON.readTree(mvc.perform(post("/api/v1/check-in").header("Authorization", "Bearer " + c[1])
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getContentAsString());
    }

    /** Starts paying on the public page and returns the Paystack reference. */
    private String startPaying(Invoice i) throws Exception {
        MvcResult r = mvc.perform(post("/pay/" + i.getPayToken()).with(csrf()).param("email", "bursar@ucc.edu.gh")).andReturn();
        assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("https://checkout.paystack.com/fake123");
        return checkouts.findAll().get(0).getReference();
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private int webhook(String reference, String signature) throws Exception {
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"" + reference + "\"}}";
        return mvc.perform(post("/api/v1/paystack/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("x-paystack-signature", signature == null ? sign(body) : signature)).andReturn().getResponse().getStatus();
    }

    @Test
    void theClientsPortalLearnsAboutAnOpenInvoiceAtCheckIn() throws Exception {
        Object[] c = client(LocalDate.now().plusDays(10), "18500");
        assertThat(checkIn(c).path("invoice").isNull()).isTrue();
        Invoice i = raise(c, "18500", LocalDate.now().plusDays(10).plusYears(1));
        JsonNode inv = checkIn(c).path("invoice");
        assertThat(inv.path("number").asText()).isEqualTo(i.getNumber());
        assertThat(inv.path("amount").asText()).isEqualTo("18500.00");
        assertThat(inv.path("payUrl").asText()).isEqualTo("https://vendor.example.com/pay/" + i.getPayToken());

        // Only one open invoice at a time
        mvc.perform(post("/clients/" + c[0] + "/invoices").with(admin()).with(csrf()).param("amount", "1")
                .param("currency", "GHS").param("coversUntil", LocalDate.now().plusYears(3).toString()).param("dueDate", LocalDate.now().toString()));
        assertThat(invoices.count()).isEqualTo(1);
    }

    @Test
    void payingOnlineRecordsThePaymentOnceAndRenewsTheKey() throws Exception {
        LocalDate renewTo = LocalDate.now().plusDays(10).plusYears(1);
        Object[] c = client(LocalDate.now().plusDays(10), "18500");
        Invoice i = raise(c, "18500", renewTo);

        // The pay page is public (the secret link is the access)
        assertThat(mvc.perform(get("/pay/" + i.getPayToken())).andReturn().getResponse().getStatus()).isEqualTo(200);
        String ref = startPaying(i);
        assertThat(CHECKOUTS.get(ref)[0]).isEqualTo(1_850_000L);   // pesewas

        mvc.perform(get("/pay/" + i.getPayToken() + "/return").param("reference", ref));
        assertThat(invoices.findById(i.getId()).orElseThrow().getStatus()).isEqualTo(Invoice.Status.PAID);
        assertThat(clients.findById((Long) c[0]).orElseThrow().getPaidUntil()).isEqualTo(renewTo);
        assertThat(payments.findAll()).hasSize(1);
        assertThat(payments.findAll().get(0).getReference()).isEqualTo(ref);

        JsonNode res = checkIn(c);
        assertThat(payload(res.path("key").asText()).path("paidUntil").asText()).isEqualTo(renewTo.toString());
        assertThat(res.path("invoice").isNull()).isTrue();

        // Paystack's webhook for the same payment, and the payer reloading the return page, change nothing
        assertThat(webhook(ref, null)).isEqualTo(200);
        mvc.perform(get("/pay/" + i.getPayToken() + "/return").param("reference", ref));
        assertThat(payments.findAll()).hasSize(1);
        // And a paid invoice can't be paid again
        mvc.perform(post("/pay/" + i.getPayToken()).with(csrf()).param("email", "bursar@ucc.edu.gh"));
        assertThat(checkouts.count()).isEqualTo(1);
    }

    @Test
    void theWebhookAloneIsEnoughButOnlyWhenSignedByPaystack() throws Exception {
        Object[] c = client(LocalDate.now().plusDays(3), "500");
        Invoice i = raise(c, "500", LocalDate.now().plusYears(1));
        String ref = startPaying(i);

        assertThat(webhook(ref, "00ff")).isEqualTo(401);
        assertThat(invoices.findById(i.getId()).orElseThrow().getStatus()).isEqualTo(Invoice.Status.OPEN);

        assertThat(webhook(ref, null)).isEqualTo(200);
        assertThat(invoices.findById(i.getId()).orElseThrow().getStatus()).isEqualTo(Invoice.Status.PAID);
        assertThat(payments.findAll()).hasSize(1);
    }

    @Test
    void anUnfinishedOrWrongAmountPaymentDoesNotCount() throws Exception {
        Object[] c = client(LocalDate.now().plusDays(3), "500");
        Invoice i = raise(c, "500", LocalDate.now().plusYears(1));

        verifyStatus = "abandoned";
        String ref = startPaying(i);
        mvc.perform(get("/pay/" + i.getPayToken() + "/return").param("reference", ref));
        assertThat(payments.count()).isZero();

        // A forged "success" with a smaller amount than the invoice
        verifyStatus = "success";
        amountDelta = -100;
        checkouts.deleteAll();
        String ref2 = startPaying(i);
        assertThat(webhook(ref2, null)).isEqualTo(200);
        assertThat(payments.count()).isZero();
        assertThat(invoices.findById(i.getId()).orElseThrow().getStatus()).isEqualTo(Invoice.Status.OPEN);
    }

    @Test
    void renewalInvoicesAreRaisedThirtyDaysAheadOnce() throws Exception {
        Object[] c = client(LocalDate.now().plusDays(20), "12000");
        assertThat(invoiceService.raiseRenewalInvoices()).isEqualTo(1);
        assertThat(invoiceService.raiseRenewalInvoices()).isZero();
        Invoice i = invoices.findAll().get(0);
        assertThat(i.getAmount()).isEqualByComparingTo("12000");
        assertThat(i.getCoversUntil()).isEqualTo(LocalDate.now().plusDays(20).plusYears(1));
        assertThat(i.getNumber()).startsWith("INV-");
        assertThat(clients.findById((Long) c[0])).isPresent();
    }

    @Test
    void aCancelledInvoiceCanNoLongerBePaid() throws Exception {
        Object[] c = client(LocalDate.now().plusDays(3), "500");
        Invoice i = raise(c, "500", LocalDate.now().plusYears(1));
        mvc.perform(post("/clients/" + c[0] + "/invoices/" + i.getId() + "/cancel").with(admin()).with(csrf()));
        assertThat(invoices.findById(i.getId()).orElseThrow().getStatus()).isEqualTo(Invoice.Status.CANCELLED);
        mvc.perform(post("/pay/" + i.getPayToken()).with(csrf()).param("email", "x@y.zz"));
        assertThat(checkouts.count()).isZero();
        assertThat(mvc.perform(get("/pay/unknown-token")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/clients/" + c[0]).with(admin())).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
