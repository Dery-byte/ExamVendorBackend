package com.vendor.web;

import com.vendor.model.Payment;
import com.vendor.service.RevenueService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** Money received: headline figures, by month, by client and by method, and the payments behind them (CSV too). */
@Controller
public class RevenueController {

    private final RevenueService revenue;

    public RevenueController(RevenueService revenue) {
        this.revenue = revenue;
    }

    @GetMapping("/revenue")
    public String revenue(@RequestParam(required = false) String preset,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(required = false) Long client,
                          @RequestParam(required = false) String method,
                          Model model) {
        RevenueService.Report r = revenue.report(preset, from, to, client, method);
        RevenueService.Filter f = r.filter();
        model.addAttribute("r", r);
        model.addAttribute("f", f);
        model.addAttribute("presets", RevenueService.Filter.PRESETS);
        // Links that keep the client and method while switching period, and the export of exactly what is shown
        model.addAttribute("keep", keep(f));
        model.addAttribute("exportQuery", periodQuery(f) + keep(f));
        return "revenue";
    }

    @GetMapping("/revenue/payments.csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String preset,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) Long client,
                                         @RequestParam(required = false) String method) {
        List<Payment> rows = revenue.paymentsFor(preset, from, to, client, method);
        RevenueService.Filter f = RevenueService.Filter.of(preset, from, to, client, method, LocalDate.now(),
                rows.stream().map(Payment::getPaidOn).min(LocalDate::compareTo).orElse(null));
        StringBuilder csv = new StringBuilder("﻿");   // so Excel reads it as UTF-8
        csv.append("Paid on,Institution,Code,Amount,Currency,Renews to,Method,Reference,Notes,Recorded by,Recorded at\r\n");
        for (Payment p : rows) {
            csv.append(String.join(",",
                    cell(p.getPaidOn().toString()), cell(p.getClient().getName()), cell(p.getClient().getCode()),
                    p.getAmount().toPlainString(), cell(p.getCurrency()), cell(p.getCoversUntil().toString()),
                    cell(p.getMethod()), cell(p.getReference()), cell(p.getNotes()), cell(p.getRecordedBy()),
                    cell(p.getRecordedAt() == null ? "" : p.getRecordedAt().toString()))).append("\r\n");
        }
        String name = "payments_" + f.from() + "_to_" + f.to() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A CSV cell: quoted, and never read by a spreadsheet as a formula. */
    static String cell(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String periodQuery(RevenueService.Filter f) {
        return "custom".equals(f.preset()) ? "from=" + f.from() + "&to=" + f.to() : "preset=" + f.preset();
    }

    private static String keep(RevenueService.Filter f) {
        StringBuilder q = new StringBuilder();
        if (f.clientId() != null) q.append("&client=").append(f.clientId());
        if (!"all".equals(f.method())) q.append("&method=").append(URLEncoder.encode(f.method(), StandardCharsets.UTF_8));
        return q.toString();
    }
}
