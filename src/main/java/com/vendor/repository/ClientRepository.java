package com.vendor.repository;

import com.vendor.model.Client;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {

    Optional<Client> findByTokenHash(String tokenHash);

    boolean existsByCodeIgnoreCase(String code);

    List<Client> findAllByOrderByNameAsc();
}
