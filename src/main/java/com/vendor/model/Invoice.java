package com.vendor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A bill for a client: paying it (online through Paystack, from its private pay link) records the
 * payment and moves the client's paid-until date to {@code coversUntil}.
 */
@Entity
@Table(name = "invoice", uniqueConstraints = {
        @UniqueConstraint(name = "uk_invoice_number", columnNames = "number"),
        @UniqueConstraint(name = "uk_invoice_pay_token", columnNames = "pay_token")},
        indexes = @Index(name = "idx_invoice_client", columnList = "client_id, status"))
@Getter @Setter
public class Invoice {

    public enum Status { OPEN, PAID, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id")
    private Client client;

    /** INV-2026-0007; set right after the first save. */
    @Column(length = 30)
    private String number;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** The client's paid-until date once this is paid. */
    @Column(name = "covers_until", nullable = false)
    private LocalDate coversUntil;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.OPEN;

    /** Secret part of the public pay link. */
    @Column(name = "pay_token", nullable = false, length = 64)
    private String payToken;

    @Column(length = 300)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "paid_reference", length = 64)
    private String paidReference;
}
