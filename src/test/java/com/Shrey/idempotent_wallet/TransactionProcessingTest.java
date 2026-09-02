package com.Shrey.idempotent_wallet;

import com.Shrey.idempotent_wallet.dto.TransactionRequest;
import com.Shrey.idempotent_wallet.entity.Wallet;
import com.Shrey.idempotent_wallet.exception.DublicateTransactionException;
import com.Shrey.idempotent_wallet.exception.InsufficientFundsException;
import com.Shrey.idempotent_wallet.repository.WalletRepository;
import com.Shrey.idempotent_wallet.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TransactionProcessingTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private WalletRepository walletRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        walletRepository.save(new Wallet(userId, new BigDecimal("500.00")));
    }

    private TransactionRequest debitRequest(UUID transactionId, UUID userId, BigDecimal amount) {
        TransactionRequest req = new TransactionRequest();
        req.setTransactionId(transactionId);
        req.setUserId(userId);
        req.setAmount(amount);
        req.setType("DEBIT");
        return req;
    }

    @Test
    @DisplayName("Processes a single valid debit transaction successfully.")
    void processesSingleValidDebitSuccessfully() {
        TransactionRequest req = debitRequest(UUID.randomUUID(), userId, new BigDecimal("250.00"));

        var response = transactionService.process(req);

        assertEquals("SUCCESS", response.getStatus());
        assertEquals(0, response.getNewBalance().compareTo(new BigDecimal("250.00")));

        System.out.println("[Happy Path] balance after single debit = " + response.getNewBalance());
    }

    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void idempotentUnderThreeSimultaneousIdenticalRequests() throws InterruptedException {
        UUID sharedTransactionId = UUID.randomUUID();
        int attempts = 3;

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch readyLatch = new CountDownLatch(attempts);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        for (int i = 0; i < attempts; i++) {
            executor.submit(() -> {
                TransactionRequest req = debitRequest(sharedTransactionId, userId, new BigDecimal("250.00"));
                readyLatch.countDown();
                try {
                    startLatch.await(); // all three threads release at the same instant
                    transactionService.process(req);
                    successCount.incrementAndGet();
                } catch (DublicateTransactionException e) {
                    duplicateCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        Wallet wallet = walletRepository.findById(userId).orElseThrow();

        System.out.println("[Idempotency] successes=" + successCount.get()
                + " duplicates=" + duplicateCount.get()
                + " finalBalance=" + wallet.getBalance());

        assertEquals(1, successCount.get(), "Exactly one of the 3 identical requests should succeed");
        assertEquals(2, duplicateCount.get(), "The other 2 should be rejected as duplicates");
        assertEquals(0, wallet.getBalance().compareTo(new BigDecimal("250.00")),
                "Balance should be deducted exactly once, not three times");
    }

    @Test
    @DisplayName("Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.")
    void raceConditionNeverAllowsNegativeBalance() throws InterruptedException {
        int requestCount = 10;
        BigDecimal debitAmount = new BigDecimal("100.00");

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);

        for (int i = 0; i < requestCount; i++) {
            executor.submit(() -> {
                TransactionRequest req = debitRequest(UUID.randomUUID(), userId, debitAmount);
                readyLatch.countDown();
                try {
                    startLatch.await();
                    transactionService.process(req);
                    successCount.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficientFundsCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        Wallet wallet = walletRepository.findById(userId).orElseThrow();

        System.out.println("[Race Condition] successes=" + successCount.get()
                + " insufficientFunds=" + insufficientFundsCount.get()
                + " finalBalance=" + wallet.getBalance());

        assertEquals(5, successCount.get(), "Only 5 of the 10 ₹100 debits should succeed against a ₹500 balance");
        assertEquals(5, insufficientFundsCount.get(), "The other 5 should fail with insufficient funds");
        assertEquals(0, wallet.getBalance().compareTo(BigDecimal.ZERO),
                "Final balance must be exactly zero, never negative");
    }
}
