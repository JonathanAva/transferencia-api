package com.izalco.transferencias.idempotencia;

/**
 * La primera solicitud con esta llave todavía se está procesando. Se traduce a {@code 409 SOLICITUD_EN_PROCESO}.
 * Ocurre, por ejemplo, si la app se rinde antes que la API y reintenta: no se duplica nada.
 */
public class SolicitudEnProcesoException extends RuntimeException {

    public SolicitudEnProcesoException() {
        super("Ya existe una solicitud en proceso con esta llave de idempotencia. Reintente en unos segundos.");
    }
}
