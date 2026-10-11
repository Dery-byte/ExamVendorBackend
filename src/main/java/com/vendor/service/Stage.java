package com.vendor.service;

import com.vendor.model.Client;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Where a client's subscription stands today, by the same rules as the exam portal
 * (SubscriptionStatus): active to paidUntil, then grace, then read-only, then suspended.
 */
public enum Stage {
    ACTIVE("Active", "ok"),
    GRACE("Grace period", "warn"),
    RESTRICTED("Read-only", "bad"),
    SUSPENDED("Suspended", "bad");

    public final String label;
    /** CSS tone for chips. */
    public final String tone;

    Stage(String label, String tone) {
        this.label = label;
        this.tone = tone;
    }

    public static Stage of(Client c, LocalDate today) {
        if (c.isSuspended()) return SUSPENDED;
        LocalDate graceEnd = c.getPaidUntil().plusDays(c.getGraceDays());
        if (!today.isAfter(c.getPaidUntil())) return ACTIVE;
        if (!today.isAfter(graceEnd)) return GRACE;
        if (!today.isAfter(graceEnd.plusDays(c.getRestrictedDays()))) return RESTRICTED;
        return SUSPENDED;
    }

    /** Days left of the paid period (negative once it has ended). */
    public static long daysPaidLeft(Client c, LocalDate today) {
        return ChronoUnit.DAYS.between(today, c.getPaidUntil());
    }
}
