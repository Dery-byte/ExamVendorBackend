package com.vendor.repository;

import com.vendor.model.VendorUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VendorUserRepository extends JpaRepository<VendorUser, Long> {

    Optional<VendorUser> findByEmail(String email);
}
