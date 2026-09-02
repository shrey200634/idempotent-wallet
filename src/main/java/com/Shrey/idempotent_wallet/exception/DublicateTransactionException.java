package com.Shrey.idempotent_wallet.exception;

public class DublicateTransactionException extends  RuntimeException{
    public  DublicateTransactionException(String message ){
        super(message);
    }
}
