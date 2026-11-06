package dev.caiorocha.orderflow.shared;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + " " + id + " não encontrado");
    }
}
