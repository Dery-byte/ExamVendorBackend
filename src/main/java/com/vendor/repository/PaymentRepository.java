package com.vendor.repository;

import com.vendor.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByClient_IdOrderByPaidOnDescIdDesc(Long clientId);
}
