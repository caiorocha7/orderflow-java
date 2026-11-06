package dev.caiorocha.orderflow.shared;

/** Conflito de estado: SKU duplicado, versão desatualizada, transição já aplicada. */
public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
