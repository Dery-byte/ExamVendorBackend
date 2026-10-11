package com.vendor.repository;

import com.vendor.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByClient_IdOrderByPaidOnDescIdDesc(Long clientId);

    /** Every payment with its client, newest first (revenue page and export). */
    @Query("SELECT p FROM Payment p JOIN FETCH p.client ORDER BY p.paidOn DESC, p.id DESC")
    List<Payment> findAllWithClient();

    @Query("SELECT p FROM Payment p JOIN FETCH p.client WHERE p.paidOn BETWEEN :from AND :to ORDER BY p.paidOn DESC, p.id DESC")
    List<Payment> findPaidBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
