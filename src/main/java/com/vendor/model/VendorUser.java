package com.vendor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Someone on the vendor's team who can sign in to this portal, with a code emailed to them. Rows are added
 * and removed directly in the database (as with the exam portal's developer_email):
 * <pre>
 *   INSERT INTO vendor_user (email, name, created_at) VALUES ('you@example.com', 'Your Name', NOW());
 *   DELETE FROM vendor_user WHERE email = 'you@example.com';
 * </pre>
 */
@Entity
@Table(name = "vendor_user", uniqueConstraints = @UniqueConstraint(name = "uk_vendor_user_email", columnNames = "email"))
@Getter @Setter
public class VendorUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stored lower-case. */
    @Column(nullable = false, length = 150)
    private String email;

    @Column(length = 120)
    private String name;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
}
