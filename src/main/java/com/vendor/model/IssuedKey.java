package com.vendor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** A signed key issued for a client. The newest one (highest seq) is what its portal receives. */
@Entity
@Table(name = "issued_key", indexes = @Index(name = "idx_issued_key_client", columnList = "client_id, seq"))
@Getter @Setter
public class IssuedKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id")
    private Client client;

    @Column(name = "key_id", nullable = false, length = 64)
    private String keyId;

    /** Epoch milliseconds when issued; portals only ever move to a higher seq. */
    @Column(nullable = false)
    private long seq;

    @Column(length = 40)
    private String plan;

    @Column(name = "paid_until", nullable = false)
    private LocalDate paidUntil;

    @Column(name = "grace_days", nullable = false)
    private int graceDays;

    @Column(name = "restricted_days", nullable = false)
    private int restrictedDays;

    @Column(nullable = false)
    private boolean suspended;

    @Column(length = 300)
    private String note;

    /** Why it was issued: payment, new terms, suspension, … */
    @Column(nullable = false, length = 200)
    private String reason;

    @Column(name = "raw_key", nullable = false, length = 2000)
    private String raw;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "issued_by", length = 150)
    private String issuedBy;

    /** First time the client's portal checked in already holding this key. */
    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;
}
