package com.vendor.web;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatting for the templates, as ${fmt.day(d)}, ${fmt.ago(t)} … */
@Component
public class Fmt {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    public String day(LocalDate d) { return d == null ? "—" : d.format(DAY); }

    public String when(LocalDateTime t) { return t == null ? "—" : t.format(WHEN); }

    public String ago(LocalDateTime t) {
        if (t == null) return "never";
        Duration d = Duration.between(t, LocalDateTime.now());
        if (d.isNegative() || d.toMinutes() < 1) return "just now";
        if (d.toMinutes() < 60) return d.toMinutes() + " min ago";
        if (d.toHours() < 24) return d.toHours() + " h ago";
        long days = d.toDays();
        return days == 1 ? "yesterday" : days + " days ago";
    }

    /** "in 12 days", "today", "3 days ago". */
    public String relDays(long days) {
        if (days == 0) return "today";
        if (days == 1) return "tomorrow";
        if (days == -1) return "yesterday";
        return days > 0 ? "in " + days + " days" : (-days) + " days ago";
    }

    public String money(String currency, BigDecimal amount) {
        return currency + " " + new DecimalFormat("#,##0.00").format(amount);
    }

    public String count(Long n) { return n == null ? "—" : String.format(Locale.ENGLISH, "%,d", n); }

    /** A state as the client's portal reports it (ACTIVE, GRACE …), in the words the pages use. */
    public String state(String s) {
        if (s == null) return "—";
        try { return com.vendor.service.Stage.valueOf(s).label; } catch (IllegalArgumentException e) { return s; }
    }
}
