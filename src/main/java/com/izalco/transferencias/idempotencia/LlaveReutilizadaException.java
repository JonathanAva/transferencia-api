package com.izalco.transferencias.idempotencia;

/** La llave ya se usó con una solicitud de contenido distinto. Se traduce a {@code 422 IDEMPOTENCY_KEY_REUTILIZADA}. */
public class LlaveReutilizadaException extends RuntimeException {

    public LlaveReutilizadaException() {
        super("La llave de idempotencia ya se usó con una solicitud diferente.");
    }
}
