package com.vendor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One client institution and the terms of its subscription. Every change to the terms issues a new
 * signed key ({@link IssuedKey}); the client's exam portal picks it up at its next check-in.
 */
@Entity
@Table(name = "client", uniqueConstraints = {
        @UniqueConstraint(name = "uk_client_code", columnNames = "code"),
        @UniqueConstraint(name = "uk_client_token", columnNames = "token_hash")})
@Getter @Setter
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Institution code written into its keys (e.g. UCC); never changes once keys are issued. */
    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "contact_name", length = 120)
    private String contactName;

    @Column(name = "contact_email", length = 150)
    private String contactEmail;

    @Column(name = "contact_phone", length = 40)
    private String contactPhone;

    @Column(name = "portal_url", length = 300)
    private String portalUrl;

    @Column(nullable = false, length = 40)
    private String plan = "standard";

    @Column(name = "paid_until", nullable = false)
    private LocalDate paidUntil;

    @Column(name = "grace_days", nullable = false)
    private int graceDays = 14;

    @Column(name = "restricted_days", nullable = false)
    private int restrictedDays = 7;

    @Column(nullable = false)
    private boolean suspended;

    @Column(name = "suspend_reason", length = 300)
    private String suspendReason;

    @Column(length = 2000)
    private String notes;

    // ── Billing ─────────────────────────────────────────────────────────

    /** What a year costs; when set, a renewal invoice is raised 30 days before paidUntil. */
    @Column(name = "annual_price", precision = 12, scale = 2)
    private java.math.BigDecimal annualPrice;

    /** The default lets the column be added to a database that already has clients. */
    @Column(nullable = false, length = 3)
    @org.hibernate.annotations.ColumnDefault("'GHS'")
    private String currency = "GHS";

    /** Where Paystack sends the receipt; falls back to the contact email. */
    @Column(name = "billing_email", length = 150)
    private String billingEmail;

    /** SHA-256 of the client's check-in token; the token itself is shown once and never stored. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** Last four characters of the token, so it can be recognised. */
    @Column(name = "token_hint", length = 8)
    private String tokenHint;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // ── What the client's portal last reported ──────────────────────────

    @Column(name = "last_check_in_at")
    private LocalDateTime lastCheckInAt;

    @Column(name = "last_check_in_ip", length = 64)
    private String lastCheckInIp;

    @Column(name = "reported_state", length = 20)
    private String reportedState;

    @Column(name = "reported_version", length = 40)
    private String reportedVersion;

    @Column(name = "reported_key_id", length = 64)
    private String reportedKeyId;

    @Column(name = "reported_key_problem", length = 300)
    private String reportedKeyProblem;

    @Column(name = "reported_portal_url", length = 300)
    private String reportedPortalUrl;

    @Column(name = "reported_students")
    private Long reportedStudents;

    @Column(name = "reported_staff")
    private Long reportedStaff;
}
