package com.Shrey.idempotent_wallet.repository;

import com.Shrey.idempotent_wallet.entity.ProcessedTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedTransactionRepository  extends JpaRepository<ProcessedTransaction , UUID> {

    boolean existsByTransactionId(UUID transactionId );
}
