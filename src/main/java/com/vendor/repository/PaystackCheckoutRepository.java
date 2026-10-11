package com.vendor.repository;

import com.vendor.model.PaystackCheckout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaystackCheckoutRepository extends JpaRepository<PaystackCheckout, Long> {

    Optional<PaystackCheckout> findByReference(String reference);
}
