package com.Shrey.idempotent_wallet.entity.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class TransactionResponse {
    private UUID transactionId ;
    private UUID userId ;
    private BigDecimal newBalance ;
    private String status ;

}
