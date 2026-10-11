package com.vendor;

import com.vendor.model.Client;
import com.vendor.model.Invoice;
import com.vendor.model.Payment;
import com.vendor.repository.*;
import com.vendor.service.RevenueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Revenue figures, filters and the CSV export, on H2. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:revenue;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.mailjet.api-key=", "app.mailjet.api-secret=", "app.paystack.secret-key=", "app.login.log-codes=false",
        "app.billing.renewal-cron=-", "app.admin.email=",
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RevenueTest {

    @Autowired MockMvc mvc;
    @Autowired RevenueService revenue;
    @Autowired ClientRepository clients;
    @Autowired PaymentRepository payments;
    @Autowired InvoiceRepository invoices;
    @Autowired PaystackCheckoutRepository checkouts;
    @Autowired IssuedKeyRepository keys;

    final LocalDate today = LocalDate.now();
    Client ucc, ktu;

    @BeforeEach
    void data() {
        checkouts.deleteAll();
        invoices.deleteAll();
        payments.deleteAll();
        keys.deleteAll();
        clients.deleteAll();
        ucc = client("UCC", "University of Cape Coast", today.plusDays(10), new BigDecimal("18500"));
        ktu = client("KTU", "Koforidua Technical University", today.plusMonths(8), null);
        pay(ucc, "18500.00", "GHS", today.withDayOfMonth(1), "Paystack (mobile money)", "INV-1");
        pay(ucc, "500.00", "GHS", today.minusYears(1), "Bank transfer", "=HYPERLINK(\"http://x\")");
        pay(ktu, "1500.00", "GHS", today.withDayOfMonth(1), "Bank transfer", "TRF-9");
        pay(ktu, "200.00", "USD", today.withDayOfMonth(1), "Paystack (card)", "INV-2");
        Invoice i = new Invoice();
        i.setClient(ktu);
        i.setAmount(new BigDecimal("9750.00"));
        i.setCurrency("GHS");
        i.setCoversUntil(today.plusYears(2));
        i.setDueDate(today.plusDays(14));
        i.setPayToken(UUID.randomUUID().toString());
        i.setCreatedAt(LocalDateTime.now());
        i.setNumber("INV-T-1");
        invoices.save(i);
    }

    private Client client(String code, String name, LocalDate paidUntil, BigDecimal price) {
        Client c = new Client();
        c.setCode(code);
        c.setName(name);
        c.setPaidUntil(paidUntil);
        c.setAnnualPrice(price);
        c.setTokenHash(UUID.randomUUID().toString().replace("-", ""));
        c.setCreatedAt(LocalDateTime.now());
        return clients.save(c);
    }

    private void pay(Client c, String amount, String currency, LocalDate on, String method, String ref) {
        Payment p = new Payment();
        p.setClient(c);
        p.setAmount(new BigDecimal(amount));
        p.setCurrency(currency);
        p.setPaidOn(on);
        p.setCoversUntil(on.plusYears(1));
        p.setMethod(method);
        p.setReference(ref);
        p.setRecordedAt(LocalDateTime.now());
        payments.save(p);
    }

    @Test
    void currenciesAreNeverAddedTogether() {
        RevenueService.Report r = revenue.report("year", null, null, null, null);
        assertThat(r.currency()).isEqualTo("GHS");
        assertThat(r.periodTotal().of("GHS")).isEqualByComparingTo("20000.00");
        assertThat(r.periodTotal().of("USD")).isEqualByComparingTo("200.00");
        assertThat(r.thisMonth().of("GHS")).isEqualByComparingTo("20000.00");
        assertThat(r.allTime().of("GHS")).isEqualByComparingTo("20500.00");
        assertThat(r.allTime().count()).isEqualTo(4);
    }

    @Test
    void owedRenewalsAndOverdueAreCounted() {
        RevenueService.Report r = revenue.report("year", null, null, null, null);
        assertThat(r.outstanding().of("GHS")).isEqualByComparingTo("9750.00");
        assertThat(r.outstanding().count()).isEqualTo(1);
        assertThat(r.due30().of("GHS")).isEqualByComparingTo("18500");   // UCC renews in 10 days at its yearly price
        assertThat(r.due30Clients()).isEqualTo(1);
    }

    @Test
    void breakdownsByClientMethodAndMonth() {
        RevenueService.Report r = revenue.report("year", null, null, null, null);
        assertThat(r.clients().get(0).name()).isEqualTo("University of Cape Coast");
        assertThat(r.clients().get(0).sharePct()).isEqualTo(93);   // 18,500 of 20,000 GHS
        assertThat(r.clients().get(1).outstanding().of("GHS")).isEqualByComparingTo("9750.00");
        assertThat(r.methods()).extracting(RevenueService.MethodRow::label)
                .containsExactly("Online · Mobile money", "Bank transfer", "Online · Card");
        assertThat(r.chart().bars()).hasSize(today.getMonthValue());   // January to this month
        assertThat(r.chart().bars().get(today.getMonthValue() - 1).amount()).isEqualByComparingTo("20000.00");
        assertThat(r.chart().axisMax()).isGreaterThanOrEqualTo(new BigDecimal("20000"));
    }

    @Test
    void filtersNarrowEverythingBelowThem() {
        assertThat(revenue.report("year", null, null, ktu.getId(), null).periodTotal().of("GHS")).isEqualByComparingTo("1500.00");
        assertThat(revenue.report("year", null, null, null, "online").periodTotal().of("GHS")).isEqualByComparingTo("18500.00");
        assertThat(revenue.report("year", null, null, null, "manual").periodTotal().of("GHS")).isEqualByComparingTo("1500.00");
        assertThat(revenue.report("all", null, null, null, null).periodTotal().of("GHS")).isEqualByComparingTo("20500.00");
        // A long custom range is shown per year
        RevenueService.Report longRange = revenue.report(null, today.minusYears(5), today, null, null);
        assertThat(longRange.chart().byYear()).isTrue();
        assertThat(longRange.chart().bars()).hasSize(6);
    }

    @Test
    void thePageRendersForEveryPeriodAndNeedsSignIn() throws Exception {
        for (String q : new String[]{"", "?preset=month", "?preset=3m", "?preset=12m", "?preset=all",
                "?from=" + today.minusYears(5) + "&to=" + today, "?preset=year&client=" + ktu.getId() + "&method=online"}) {
            MockHttpServletResponse res = mvc.perform(get("/revenue" + q).with(user("v@x.com").roles("VENDOR"))).andReturn().getResponse();
            assertThat(res.getStatus()).as(q).isEqualTo(200);
            assertThat(res.getContentAsString()).contains("Revenue", "By client", "By payment method");
        }
        assertThat(mvc.perform(get("/revenue")).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
        assertThat(mvc.perform(get("/revenue/payments.csv")).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
    }

    @Test
    void theExportOpensSafelyInExcel() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/revenue/payments.csv?preset=all").with(user("v@x.com").roles("VENDOR")))
                .andReturn().getResponse();
        assertThat(res.getContentType()).startsWith("text/csv");
        assertThat(res.getHeader("Content-Disposition")).contains("attachment").contains(".csv");
        String csv = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Paid on,Institution,Code,Amount");
        assertThat(csv.lines().count()).isEqualTo(5);   // header + 4 payments
        assertThat(csv).contains("\"'=HYPERLINK(\"\"http://x\"\")\"");   // a formula is shown, never run
    }
}
