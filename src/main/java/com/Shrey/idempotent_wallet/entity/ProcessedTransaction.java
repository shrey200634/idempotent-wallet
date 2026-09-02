package com.Shrey.idempotent_wallet.entity;


import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name ="processed_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedTransaction {

    @Id
    private UUID transactionId;

    private UUID userId;

    private BigDecimal amount;

    private String type;

    private Instant processedAt;

    public ProcessedTransaction(UUID transactionId, UUID userId, BigDecimal amount, String type) {
        this.transactionId = transactionId;
        this.userId = userId;
        this.amount = amount;
        this.type = type;
        this.processedAt = Instant.now();
    }
}
