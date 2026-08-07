package com.pixsim.transaction.infrastructure;

import com.pixsim.transaction.domain.PixTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PixTransactionRepository extends JpaRepository<PixTransaction, UUID> {
    Optional<PixTransaction> findByEndToEndId(String endToEndId);
}
