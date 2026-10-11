package com.vendor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** What happened in the vendor portal, and who did it: the audit trail. */
@Entity
@Table(name = "activity", indexes = @Index(name = "idx_activity_client", columnList = "client_id, at"))
@Getter @Setter
public class Activity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime at;

    /** Null for things not about one client (sign-ins, password changes). */
    @Column(name = "client_id")
    private Long clientId;

    @Column(length = 150)
    private String actor;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(length = 1000)
    private String details;
}
