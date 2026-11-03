package com.izalco.transferencias.dominio;

import java.util.UUID;

/** No existe una transferencia con el id consultado. Se traduce a {@code 404 TRANSFERENCIA_NO_ENCONTRADA}. */
public class TransferenciaNoEncontradaException extends RuntimeException {

    private final UUID id;

    public TransferenciaNoEncontradaException(UUID id) {
        super("No existe una transferencia con id " + id);
        this.id = id;
    }

    public UUID getId() {
        return id;
    }
}
