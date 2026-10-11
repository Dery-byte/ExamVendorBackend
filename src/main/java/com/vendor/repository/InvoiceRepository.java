package com.vendor.repository;

import com.vendor.model.Invoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    @Query("SELECT i FROM Invoice i JOIN FETCH i.client WHERE i.payToken = :token")
    Optional<Invoice> findByPayToken(@Param("token") String payToken);

    List<Invoice> findByClient_IdOrderByCreatedAtDescIdDesc(Long clientId);

    /** The invoice a client's portal shows (newest open one). */
    Optional<Invoice> findFirstByClient_IdAndStatusOrderByCreatedAtDescIdDesc(Long clientId, Invoice.Status status);

    boolean existsByClient_IdAndStatus(Long clientId, Invoice.Status status);

    long countByStatus(Invoice.Status status);

    /** Held while a payment is recorded, so two confirmations of one payment can't both record it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.id = :id")
    Optional<Invoice> lockById(@Param("id") Long id);
}
