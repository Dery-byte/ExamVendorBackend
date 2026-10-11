package com.vendor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vendor.model.Client;
import com.vendor.repository.ClientRepository;
import com.vendor.repository.IssuedKeyRepository;
import com.vendor.repository.PaymentRepository;
import com.vendor.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Base64;

import static com.vendor.service.KeySignerTest.payload;
import static com.vendor.service.KeySignerTest.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The whole vendor portal on H2: sign-in by emailed code, clients, payments, suspension and the check-in API. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vendor;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.mailjet.api-key=", "app.mailjet.api-secret=", "app.login.log-codes=false",
        "app.admin.email=Admin@Vendor.test",
        "server.servlet.session.cookie.secure=false",
})
@AutoConfigureMockMvc
class VendorPortalIntegrationTest {

    static final KeyPair PAIR;
    static {
        try { PAIR = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry r) {
        r.add("app.signing.private-key", () -> Base64.getEncoder().encodeToString(PAIR.getPrivate().getEncoded()));
    }

    static final String ADMIN = "admin@vendor.test";
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired ClientRepository clients;
    @Autowired IssuedKeyRepository keys;
    @Autowired PaymentRepository payments;
    @Autowired AccountService accounts;

    @BeforeEach
    void clean() {
        payments.deleteAll();
        keys.deleteAll();
        clients.deleteAll();
    }

    private static RequestPostProcessor admin() { return user(ADMIN).roles("VENDOR"); }

    /** Adds a client through the form and returns its id and check-in token. */
    private Object[] addClient(String code, LocalDate paidUntil) throws Exception {
        MvcResult r = mvc.perform(post("/clients").with(admin()).with(csrf())
                .param("code", code).param("name", "Institution " + code).param("plan", "standard")
                .param("paidUntil", paidUntil.toString()).param("graceDays", "14").param("restrictedDays", "7"))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        Client c = clients.findAll().stream().filter(x -> x.getCode().equals(code)).findFirst().orElseThrow();
        return new Object[]{c.getId(), r.getFlashMap().get("newToken")};
    }

    private MvcResult checkIn(String token, String body) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/v1/check-in").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        return mvc.perform(req).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception { return JSON.readTree(r.getResponse().getContentAsString()); }

    /** Asks for a code, then swaps in one the test knows (the real one is emailed). */
    private MockHttpSession askForCode(String email) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult r = mvc.perform(post("/login/code").session(session).with(csrf()).param("email", email)).andReturn();
        assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/login/code");
        return session;
    }

    private String enter(MockHttpSession session, String code) throws Exception {
        return mvc.perform(post("/login/verify").session(session).with(csrf()).param("code", code)).andReturn()
                .getResponse().getRedirectedUrl();
    }

    @Test
    void signingInTakesACodeEmailedToAnApprovedAddress() throws Exception {
        assertThat(mvc.perform(get("/")).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");

        MockHttpSession session = askForCode("Admin@Vendor.test");   // the first account, from VENDOR_ADMIN_EMAIL
        String code = accounts.issue(ADMIN);
        assertThat(enter(session, code)).isEqualTo("/");
        assertThat(mvc.perform(get("/").session(session)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/account").session(session)).andReturn().getResponse().getContentAsString()).contains(ADMIN);

        // A code works once
        MockHttpSession again = askForCode(ADMIN);
        assertThat(enter(again, code)).isEqualTo("/login/code");
    }

    @Test
    void anUnknownAddressGetsTheSameReplyButCanNeverSignIn() throws Exception {
        MockHttpSession session = askForCode("stranger@example.com");
        accounts.issue("stranger@example.com");   // even if a code somehow existed
        assertThat(enter(session, "000000")).isEqualTo("/login/code");
        assertThat(mvc.perform(get("/").session(session)).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
    }

    @Test
    void fiveWrongCodesUseTheCodeUp() throws Exception {
        MockHttpSession session = askForCode(ADMIN);
        String code = accounts.issue(ADMIN);
        String wrong = code.equals("111111") ? "222222" : "111111";
        for (int i = 0; i < AccountService.MAX_ATTEMPTS; i++) assertThat(enter(session, wrong)).isEqualTo("/login/code");
        assertThat(enter(session, code)).isEqualTo("/login/code");
        assertThat(mvc.perform(get("/").session(session)).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
    }

    @Test
    void thereIsNoPasswordSignIn() throws Exception {
        MvcResult r = mvc.perform(post("/login").with(csrf()).param("email", ADMIN).param("password", "anything")).andReturn();
        assertThat(r.getResponse().getStatus()).isNotEqualTo(302);
        assertThat(mvc.perform(get("/").session((MockHttpSession) r.getRequest().getSession())).andReturn()
                .getResponse().getRedirectedUrl()).endsWith("/login");
    }

    @Test
    void formsRefuseRequestsWithoutTheCsrfToken() throws Exception {
        assertThat(mvc.perform(post("/clients").with(admin()).param("code", "X").param("name", "X"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void addingAClientIssuesItsFirstKeyAndAToken() throws Exception {
        Object[] c = addClient("UCC", LocalDate.now().plusMonths(6));
        assertThat((String) c[1]).startsWith("vpt_");
        assertThat(keys.findFirstByClient_IdOrderBySeqDesc((Long) c[0])).isPresent();
        // The same code twice is refused
        mvc.perform(post("/clients").with(admin()).with(csrf()).param("code", "ucc").param("name", "Again")
                .param("plan", "standard").param("paidUntil", LocalDate.now().toString()));
        assertThat(clients.count()).isEqualTo(1);
    }

    @Test
    void checkInHandsBackTheNewestSignedKeyAndRecordsWhatThePortalSays() throws Exception {
        Object[] c = addClient("UCC", LocalDate.of(2027, 1, 31));
        assertThat(checkIn(null, "{}").getResponse().getStatus()).isEqualTo(401);
        assertThat(checkIn("vpt_wrong", "{}").getResponse().getStatus()).isEqualTo(401);

        JsonNode res = json(checkIn((String) c[1], "{\"state\":\"GRACE\",\"appVersion\":\"1.4\",\"students\":1200,\"staff\":80}"));
        String key = res.path("key").asText();
        assertThat(verify(key, PAIR.getPublic())).isTrue();
        assertThat(payload(key).path("inst").asText()).isEqualTo("UCC");
        assertThat(payload(key).path("paidUntil").asText()).isEqualTo("2027-01-31");

        Client saved = clients.findById((Long) c[0]).orElseThrow();
        assertThat(saved.getLastCheckInAt()).isNotNull();
        assertThat(saved.getReportedState()).isEqualTo("GRACE");
        assertThat(saved.getReportedStudents()).isEqualTo(1200L);

        // Reporting the key back marks it delivered
        String id = payload(key).path("id").asText();
        checkIn((String) c[1], "{\"installedKeyId\":\"" + id + "\",\"state\":\"ACTIVE\"}");
        assertThat(keys.findByClient_IdAndKeyId((Long) c[0], id).orElseThrow().getDeliveredAt()).isNotNull();
    }

    @Test
    void aPaymentMovesThePaidDateOnAndIssuesANewerKey() throws Exception {
        Object[] c = addClient("UCC", LocalDate.of(2026, 10, 1));
        long firstSeq = payload(json(checkIn((String) c[1], "{}")).path("key").asText()).path("seq").asLong();

        assertThat(mvc.perform(post("/clients/" + c[0] + "/payments").with(admin()).with(csrf())
                .param("amount", "15000").param("currency", "GHS").param("paidOn", "2026-10-11")
                .param("coversUntil", "2027-10-01").param("method", "Bank transfer").param("reference", "INV-104"))
                .andReturn().getResponse().getStatus()).isEqualTo(302);

        assertThat(clients.findById((Long) c[0]).orElseThrow().getPaidUntil()).isEqualTo(LocalDate.of(2027, 10, 1));
        JsonNode key = payload(json(checkIn((String) c[1], "{}")).path("key").asText());
        assertThat(key.path("paidUntil").asText()).isEqualTo("2027-10-01");
        assertThat(key.path("seq").asLong()).isGreaterThan(firstSeq);
        assertThat(payments.findByClient_IdOrderByPaidOnDescIdDesc((Long) c[0])).hasSize(1);
    }

    @Test
    void aPaymentDatedBackwardsIsRefused() throws Exception {
        Object[] c = addClient("UCC", LocalDate.of(2026, 10, 1));
        mvc.perform(post("/clients/" + c[0] + "/payments").with(admin()).with(csrf())
                .param("amount", "10").param("currency", "GHS").param("paidOn", "2026-10-11").param("coversUntil", "2026-01-01"));
        assertThat(payments.count()).isZero();
    }

    @Test
    void suspendingAndResumingAreSignedKeysToo() throws Exception {
        Object[] c = addClient("UCC", LocalDate.now().plusYears(1));
        mvc.perform(post("/clients/" + c[0] + "/suspend").with(admin()).with(csrf()).param("reason", "Contract ended"));
        JsonNode suspended = payload(json(checkIn((String) c[1], "{}")).path("key").asText());
        assertThat(suspended.path("suspended").asBoolean()).isTrue();
        assertThat(suspended.path("note").asText()).isEqualTo("Contract ended");

        mvc.perform(post("/clients/" + c[0] + "/resume").with(admin()).with(csrf()));
        JsonNode resumed = payload(json(checkIn((String) c[1], "{}")).path("key").asText());
        assertThat(resumed.path("suspended").asBoolean(false)).isFalse();
        assertThat(resumed.path("seq").asLong()).isGreaterThan(suspended.path("seq").asLong());
    }

    @Test
    void replacingTheTokenStopsTheOldOne() throws Exception {
        Object[] c = addClient("UCC", LocalDate.now().plusYears(1));
        MvcResult r = mvc.perform(post("/clients/" + c[0] + "/token").with(admin()).with(csrf())).andReturn();
        String fresh = (String) r.getFlashMap().get("newToken");
        assertThat(checkIn((String) c[1], "{}").getResponse().getStatus()).isEqualTo(401);
        assertThat(checkIn(fresh, "{}").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void everyPageRenders() throws Exception {
        Object[] c = addClient("UCC", LocalDate.now().plusDays(10));
        checkIn((String) c[1], "{\"state\":\"ACTIVE\",\"students\":5}");
        for (String path : new String[]{"/", "/?show=attention&q=ucc", "/clients/new", "/clients/" + c[0],
                "/clients/" + c[0] + "/edit", "/account"}) {
            MvcResult res = mvc.perform(get(path).with(admin())).andReturn();
            assertThat(res.getResponse().getStatus()).as(path).isEqualTo(200);
        }
        assertThat(mvc.perform(get("/clients/999999").with(admin())).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/login")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void tooManyBadTokensFromOneAddressAreSlowedDown() throws Exception {
        int last = 0;
        for (int i = 0; i < 21; i++) last = mvc.perform(post("/api/v1/check-in").header("Authorization", "Bearer vpt_nope")
                .with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })).andReturn().getResponse().getStatus();
        assertThat(last).isEqualTo(429);
    }
}
