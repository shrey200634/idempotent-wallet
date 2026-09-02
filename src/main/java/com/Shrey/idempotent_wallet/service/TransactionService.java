package com.Shrey.idempotent_wallet.service;

import com.Shrey.idempotent_wallet.dto.TransactionRequest;
import com.Shrey.idempotent_wallet.dto.TransactionResponse;
import com.Shrey.idempotent_wallet.entity.ProcessedTransaction;
import com.Shrey.idempotent_wallet.entity.Wallet;
import com.Shrey.idempotent_wallet.exception.DublicateTransactionException;
import com.Shrey.idempotent_wallet.exception.InsufficientFundsException;
import com.Shrey.idempotent_wallet.exception.WalletNotFoundException;
import com.Shrey.idempotent_wallet.repository.ProcessedTransactionRepository;
import com.Shrey.idempotent_wallet.repository.WalletRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final WalletRepository walletRepository;
    private final ProcessedTransactionRepository processedTransactionRepository;


    @Transactional
    public TransactionResponse process(TransactionRequest request) {
        Wallet wallet = walletRepository.findByIdForUpdate(request.getUserId())
                .orElseThrow(() -> new WalletNotFoundException(
                        "No wallet found for userId " + request.getUserId()));

        if (processedTransactionRepository.existsByTransactionId(request.getTransactionId())) {
            throw new DublicateTransactionException(
                    "transactionId " + request.getTransactionId() + " was already processed");
        }

        BigDecimal amount = request.getAmount();

        if ("DEBIT".equals(request.getType())) {
            if (wallet.getBalance().compareTo(amount) < 0) {
                throw new InsufficientFundsException(
                        "Insufficient balance for userId " + request.getUserId());
            }
            wallet.setBalance(wallet.getBalance().subtract(amount));
        } else {
            wallet.setBalance(wallet.getBalance().add(amount));
        }

        walletRepository.save(wallet);

        try {
            processedTransactionRepository.save(new ProcessedTransaction(
                    request.getTransactionId(), request.getUserId(), amount, request.getType()));
        } catch (DataIntegrityViolationException e) {

            throw new DublicateTransactionException(
                    "transactionId " + request.getTransactionId() + " was already processed");
        }

        return new TransactionResponse(request.getTransactionId(), request.getUserId(),
                wallet.getBalance(), "SUCCESS");
    }
}
