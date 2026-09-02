package com.Shrey.idempotent_wallet.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "wallets")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet {

    @Id
    private UUID userId ;

    @Column(nullable = false, precision = 19 , scale = 2)
    private BigDecimal balance ;

    @Version
    private Long version ;

    public Wallet(UUID userId, BigDecimal balance) {
        this.userId = userId;
        this.balance = balance;
    }

}
