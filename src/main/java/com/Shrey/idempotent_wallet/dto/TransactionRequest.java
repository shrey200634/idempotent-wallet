package com.Shrey.idempotent_wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class TransactionRequest {


    @NotNull
    private UUID transactionId ;

    @NotNull
    private UUID userId ;

    @NotNull
    @DecimalMin(value = "0.01")
    private BigDecimal amount ;

    @NotNull
    @Pattern(regexp = "DEBIT/CREDIT")
    private String type ;
}


