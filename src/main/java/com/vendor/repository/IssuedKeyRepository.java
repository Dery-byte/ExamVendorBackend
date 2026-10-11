package com.vendor.repository;

import com.vendor.model.IssuedKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IssuedKeyRepository extends JpaRepository<IssuedKey, Long> {

    /** The key the client's portal should have. */
    Optional<IssuedKey> findFirstByClient_IdOrderBySeqDesc(Long clientId);

    Optional<IssuedKey> findByClient_IdAndKeyId(Long clientId, String keyId);

    List<IssuedKey> findTop20ByClient_IdOrderBySeqDesc(Long clientId);
}
