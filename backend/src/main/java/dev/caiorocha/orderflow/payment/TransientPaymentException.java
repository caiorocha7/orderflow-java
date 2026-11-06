package dev.caiorocha.orderflow.payment;

/** Falha temporária do gateway (timeout, indisponibilidade). Retentável. */
public class TransientPaymentException extends RuntimeException {

    public TransientPaymentException(String message) {
        super(message);
    }
}
