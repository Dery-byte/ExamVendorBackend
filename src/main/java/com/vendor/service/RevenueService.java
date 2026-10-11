package com.vendor.service;

import com.vendor.model.Client;
import com.vendor.model.Invoice;
import com.vendor.model.Payment;
import com.vendor.repository.ClientRepository;
import com.vendor.repository.InvoiceRepository;
import com.vendor.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Money received from clients: headline figures, by month, by client and by payment method, for a
 * chosen period. Amounts are always kept per currency: GHS and USD are never added together.
 */
@Service
public class RevenueService {

    /** Rows shown on the page; the export has them all. */
    public static final int PAGE_ROWS = 100;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_SHORT = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);

    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final ClientRepository clients;

    public RevenueService(PaymentRepository payments, InvoiceRepository invoices, ClientRepository clients) {
        this.payments = payments;
        this.invoices = invoices;
        this.clients = clients;
    }

    // ── What the page asked for ──────────────────────────────────────────

    /** The period and filters. {@code method}: all, online (Paystack) or manual. */
    public record Filter(String preset, LocalDate from, LocalDate to, Long clientId, String method) {

        public static final List<String[]> PRESETS = List.of(
                new String[]{"month", "This month"}, new String[]{"3m", "Last 3 months"}, new String[]{"year", "This year"},
                new String[]{"12m", "Last 12 months"}, new String[]{"all", "All time"});

        /** Reads the request; anything missing or odd falls back to "this year". */
        public static Filter of(String preset, LocalDate from, LocalDate to, Long clientId, String method, LocalDate today, LocalDate earliest) {
            String m = "online".equals(method) || "manual".equals(method) ? method : "all";
            if (from != null || to != null) {
                LocalDate f = from != null ? from : (to != null ? to.withDayOfYear(1) : today.withDayOfYear(1));
                LocalDate t = to != null ? to : today;
                if (t.isBefore(f)) { LocalDate x = f; f = t; t = x; }
                return new Filter("custom", f, t, clientId, m);
            }
            String p = preset == null ? "year" : preset;
            return switch (p) {
                case "month" -> new Filter(p, today.withDayOfMonth(1), today, clientId, m);
                case "3m" -> new Filter(p, today.minusMonths(2).withDayOfMonth(1), today, clientId, m);
                case "12m" -> new Filter(p, today.minusMonths(11).withDayOfMonth(1), today, clientId, m);
                case "all" -> new Filter(p, earliest == null || earliest.isAfter(today) ? today.withDayOfYear(1) : earliest, today, clientId, m);
                default -> new Filter("year", today.withDayOfYear(1), today, clientId, m);
            };
        }

        boolean matches(Payment p) {
            if (clientId != null && !clientId.equals(p.getClient().getId())) return false;
            if ("online".equals(method)) return isOnline(p);
            if ("manual".equals(method)) return !isOnline(p);
            return true;
        }
    }

    // ── Money, kept per currency ─────────────────────────────────────────

    /** Amounts per currency, largest first. */
    public static final class Totals {
        private final Map<String, BigDecimal> byCurrency = new HashMap<>();
        private int count;

        void add(String currency, BigDecimal amount) {
            byCurrency.merge(currency, amount, BigDecimal::add);
            count++;
        }

        public int count() { return count; }

        public boolean isEmpty() { return byCurrency.isEmpty(); }

        public BigDecimal of(String currency) { return byCurrency.getOrDefault(currency, BigDecimal.ZERO); }

        /** currency → amount, largest first. */
        public List<Map.Entry<String, BigDecimal>> entries() {
            return byCurrency.entrySet().stream()
                    .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .toList();
        }

        /** Every currency except the given one, for "+ USD 50.00" lines. */
        public List<Map.Entry<String, BigDecimal>> others(String currency) {
            return entries().stream().filter(e -> !e.getKey().equals(currency)).toList();
        }
    }

    // ── The report ───────────────────────────────────────────────────────

    /**
     * One month (or year) of the chart. {@code heightPct}: bar height on the 0–100 scale. {@code labelClass}: its x label is
     * shown everywhere (""), hidden on phones ("x-minor") or hidden (“x-hide”) so labels never collide.
     */
    public record Bar(String label, String shortLabel, BigDecimal amount, int payments, int heightPct, boolean labelled, String labelClass) {}

    /** The chart for one currency: bars, the axis maximum and its four ticks. */
    public record Chart(String currency, List<Bar> bars, BigDecimal axisMax, List<BigDecimal> ticks, boolean byYear) {
        public boolean empty() { return bars.stream().allMatch(b -> b.amount().signum() == 0); }
    }

    public record ClientRow(Long id, String name, String code, Totals paid, LocalDate lastPaid, Totals outstanding, int sharePct) {}

    public record MethodRow(String label, boolean online, Totals paid, int sharePct) {}

    public record Report(Filter filter, Totals periodTotal, Totals thisMonth, Totals lastMonth, Totals thisYear, Totals allTime,
                         Totals outstanding, Totals due30, int due30Clients, int due30Unpriced, int overdueClients,
                         String currency, Chart chart, List<ClientRow> clients, List<MethodRow> methods,
                         List<Payment> payments, int paymentCount, List<Client> clientOptions) {
        public boolean truncated() { return paymentCount > payments.size(); }
    }

    @Transactional(readOnly = true)
    public Report report(String preset, LocalDate from, LocalDate to, Long clientId, String method) {
        LocalDate today = LocalDate.now();
        List<Payment> all = payments.findAllWithClient();
        LocalDate earliest = all.stream().map(Payment::getPaidOn).min(Comparator.naturalOrder()).orElse(null);
        Filter f = Filter.of(preset, from, to, clientId, method, today, earliest);

        // Headline figures: fixed periods, every client and method (they sit above the filters)
        Totals thisMonth = new Totals(), lastMonth = new Totals(), thisYear = new Totals(), allTime = new Totals();
        YearMonth now = YearMonth.from(today);
        for (Payment p : all) {
            allTime.add(p.getCurrency(), p.getAmount());
            YearMonth ym = YearMonth.from(p.getPaidOn());
            if (ym.equals(now)) thisMonth.add(p.getCurrency(), p.getAmount());
            if (ym.equals(now.minusMonths(1))) lastMonth.add(p.getCurrency(), p.getAmount());
            if (p.getPaidOn().getYear() == today.getYear()) thisYear.add(p.getCurrency(), p.getAmount());
        }
        Totals outstanding = new Totals();
        Map<Long, Totals> outstandingByClient = new HashMap<>();
        for (Invoice i : invoices.findWithClientByStatus(Invoice.Status.OPEN)) {
            outstanding.add(i.getCurrency(), i.getAmount());
            outstandingByClient.computeIfAbsent(i.getClient().getId(), k -> new Totals()).add(i.getCurrency(), i.getAmount());
        }
        Totals due30 = new Totals();
        int due30Clients = 0, due30Unpriced = 0, overdue = 0;
        List<Client> everyClient = clients.findAllByOrderByNameAsc();
        for (Client c : everyClient) {
            if (c.isSuspended()) continue;
            if (c.getPaidUntil().isBefore(today)) { overdue++; continue; }
            if (!c.getPaidUntil().isAfter(today.plusDays(30))) {
                due30Clients++;
                if (c.getAnnualPrice() != null) due30.add(c.getCurrency(), c.getAnnualPrice());
                else due30Unpriced++;
            }
        }

        // The chosen period
        List<Payment> inPeriod = all.stream()
                .filter(p -> !p.getPaidOn().isBefore(f.from()) && !p.getPaidOn().isAfter(f.to()))
                .filter(f::matches)
                .toList();
        Totals periodTotal = new Totals();
        inPeriod.forEach(p -> periodTotal.add(p.getCurrency(), p.getAmount()));
        String currency = periodTotal.isEmpty()
                ? (allTime.isEmpty() ? "GHS" : allTime.entries().get(0).getKey())
                : periodTotal.entries().get(0).getKey();

        return new Report(f, periodTotal, thisMonth, lastMonth, thisYear, allTime, outstanding, due30, due30Clients, due30Unpriced, overdue,
                currency, chart(inPeriod, f, currency),
                byClient(inPeriod, outstandingByClient, everyClient.stream().collect(Collectors.toMap(Client::getId, c -> c)), currency),
                byMethod(inPeriod, currency),
                inPeriod.stream().limit(PAGE_ROWS).toList(), inPeriod.size(), everyClient);
    }

    /** All payments in the filter, for the CSV export. */
    @Transactional(readOnly = true)
    public List<Payment> paymentsFor(String preset, LocalDate from, LocalDate to, Long clientId, String method) {
        List<Payment> all = payments.findAllWithClient();
        LocalDate earliest = all.stream().map(Payment::getPaidOn).min(Comparator.naturalOrder()).orElse(null);
        Filter f = Filter.of(preset, from, to, clientId, method, LocalDate.now(), earliest);
        return all.stream().filter(p -> !p.getPaidOn().isBefore(f.from()) && !p.getPaidOn().isAfter(f.to())).filter(f::matches).toList();
    }

    // ── Breakdowns ───────────────────────────────────────────────────────

    /** Money per month in the period (per year past three years), in the main currency. */
    static Chart chart(List<Payment> inPeriod, Filter f, String currency) {
        YearMonth first = YearMonth.from(f.from()), last = YearMonth.from(f.to());
        boolean byYear = first.plusMonths(36).isBefore(last);
        LinkedHashMap<String, BigDecimal[]> slots = new LinkedHashMap<>();   // key -> [amount, count]
        List<String[]> labels = new ArrayList<>();                         // [label, short]
        if (byYear) {
            for (int y = first.getYear(); y <= last.getYear(); y++) {
                slots.put(String.valueOf(y), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                labels.add(new String[]{String.valueOf(y), String.valueOf(y)});
            }
        } else {
            for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
                slots.put(m.toString(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                // Short x labels: the year at each January, the month otherwise (full names are in the tooltip and table)
                labels.add(new String[]{m.format(MONTH), m.getMonthValue() == 1 ? String.valueOf(m.getYear()) : m.format(MONTH_SHORT)});
            }
        }
        for (Payment p : inPeriod) {
            if (!p.getCurrency().equals(currency)) continue;
            String key = byYear ? String.valueOf(p.getPaidOn().getYear()) : YearMonth.from(p.getPaidOn()).toString();
            BigDecimal[] s = slots.get(key);
            if (s != null) { s[0] = s[0].add(p.getAmount()); s[1] = s[1].add(BigDecimal.ONE); }
        }
        List<BigDecimal[]> values = new ArrayList<>(slots.values());
        BigDecimal max = values.stream().map(v -> v[0]).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal step = niceStep(max.divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP));
        BigDecimal axisMax = step.multiply(BigDecimal.valueOf(4));
        List<BigDecimal> ticks = List.of(BigDecimal.ZERO, step, step.multiply(BigDecimal.valueOf(2)), step.multiply(BigDecimal.valueOf(3)), axisMax);

        int n = values.size();
        int maxIndex = -1, lastNonZero = -1;
        for (int i = 0; i < n; i++) {
            if (values.get(i)[0].signum() > 0) lastNonZero = i;
            if (maxIndex < 0 || values.get(i)[0].compareTo(values.get(maxIndex)[0]) > 0) maxIndex = i;
        }
        int every = n <= 12 ? 1 : n <= 24 ? 2 : 3;   // x labels that fit
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal amount = values.get(i)[0];
            int pct = axisMax.signum() == 0 ? 0 : amount.multiply(BigDecimal.valueOf(100)).divide(axisMax, 0, RoundingMode.HALF_UP).intValue();
            if (amount.signum() > 0 && pct == 0) pct = 1;   // a payment is never invisible
            // Label sparingly: the highest bar and the latest one with money
            boolean labelled = amount.signum() > 0 && (i == maxIndex || i == lastNonZero);
            String labelClass = i % every != 0 ? "x-hide" : (n > 6 && (i / every) % 2 == 1 ? "x-minor" : "");
            bars.add(new Bar(labels.get(i)[0], labels.get(i)[1], amount, values.get(i)[1].intValue(), Math.min(pct, 100), labelled, labelClass));
        }
        return new Chart(currency, bars, axisMax, ticks, byYear);
    }

    /** 1, 2, 2.5 or 5 × a power of ten, at least the given value (0 → 1). */
    static BigDecimal niceStep(BigDecimal raw) {
        if (raw.signum() <= 0) return BigDecimal.ONE;
        double r = raw.doubleValue();
        double mag = Math.pow(10, Math.floor(Math.log10(r)));
        double f = r / mag;
        double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
        return BigDecimal.valueOf(nice * mag).stripTrailingZeros();
    }

    /** Each client's payments in the period (share of the period's total), with what they still owe on open invoices. */
    static List<ClientRow> byClient(List<Payment> inPeriod, Map<Long, Totals> outstanding, Map<Long, Client> everyClient, String currency) {
        Map<Long, Totals> paid = new LinkedHashMap<>();
        Map<Long, LocalDate> lastPaid = new HashMap<>();
        for (Payment p : inPeriod) {
            Long id = p.getClient().getId();
            paid.computeIfAbsent(id, k -> new Totals()).add(p.getCurrency(), p.getAmount());
            lastPaid.merge(id, p.getPaidOn(), (a, b) -> a.isAfter(b) ? a : b);
        }
        BigDecimal total = paid.values().stream().map(t -> t.of(currency)).reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<Long> ids = new LinkedHashSet<>(paid.keySet());
        ids.addAll(outstanding.keySet());
        List<ClientRow> rows = new ArrayList<>();
        for (Long id : ids) {
            Client c = everyClient.get(id);
            if (c == null) continue;
            Totals t = paid.getOrDefault(id, new Totals());
            rows.add(new ClientRow(id, c.getName(), c.getCode(), t, lastPaid.get(id), outstanding.getOrDefault(id, new Totals()),
                    pct(t.of(currency), total)));
        }
        rows.sort(Comparator.comparing((ClientRow r) -> r.paid().of(currency)).reversed().thenComparing(ClientRow::name));
        return rows;
    }

    static List<MethodRow> byMethod(List<Payment> inPeriod, String currency) {
        Map<String, Totals> grouped = new LinkedHashMap<>();
        Map<String, Boolean> online = new HashMap<>();
        for (Payment p : inPeriod) {
            String label = methodLabel(p);
            grouped.computeIfAbsent(label, k -> new Totals()).add(p.getCurrency(), p.getAmount());
            online.put(label, isOnline(p));
        }
        BigDecimal total = grouped.values().stream().map(t -> t.of(currency)).reduce(BigDecimal.ZERO, BigDecimal::add);
        return grouped.entrySet().stream()
                .map(e -> new MethodRow(e.getKey(), online.get(e.getKey()), e.getValue(), pct(e.getValue().of(currency), total)))
                .sorted(Comparator.comparing((MethodRow r) -> r.paid().of(currency)).reversed())
                .toList();
    }

    static boolean isOnline(Payment p) {
        return p.getMethod() != null && p.getMethod().toLowerCase(Locale.ROOT).startsWith("paystack");
    }

    /** "Paystack (mobile money)" → "Online · Mobile money"; manual methods as recorded. */
    static String methodLabel(Payment p) {
        String m = p.getMethod() == null || p.getMethod().isBlank() ? "Not recorded" : p.getMethod().trim();
        if (!isOnline(p)) return m;
        int open = m.indexOf('('), close = m.lastIndexOf(')');
        String channel = open > 0 && close > open ? m.substring(open + 1, close).trim() : "";
        return channel.isEmpty() ? "Online (Paystack)" : "Online · " + Character.toUpperCase(channel.charAt(0)) + channel.substring(1);
    }

    private static int pct(BigDecimal part, BigDecimal whole) {
        if (whole.signum() <= 0 || part.signum() <= 0) return 0;
        int p = part.multiply(BigDecimal.valueOf(100)).divide(whole, 0, RoundingMode.HALF_UP).intValue();
        return Math.max(1, Math.min(100, p));
    }
}
