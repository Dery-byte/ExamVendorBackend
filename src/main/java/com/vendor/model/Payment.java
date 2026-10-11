package com.vendor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A payment received from a client, and the date it pays the subscription up to. */
@Entity
@Table(name = "payment", indexes = @Index(name = "idx_payment_client", columnList = "client_id, paid_on"))
@Getter @Setter
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id")
    private Client client;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(name = "covers_until", nullable = false)
    private LocalDate coversUntil;

    @Column(length = 30)
    private String method;

    @Column(length = 100)
    private String reference;

    @Column(length = 500)
    private String notes;

    @Column(name = "recorded_by", length = 150)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;
}
