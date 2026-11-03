package com.izalco.transferencias.core;

/**
 * La solicitud llegó al core pero la respuesta no llegó dentro del timeout de <b>lectura</b>.
 * El core pudo haberla procesado: el resultado es desconocido.
 * <p>
 * Se traduce a {@code 504 CORE_TIMEOUT}, la transferencia queda {@code PENDIENTE_CONFIRMACION}
 * y la llave de idempotencia <b>no</b> se libera. Reintentar aquí con otra llave es exactamente
 * lo que causó los duplicados del incidente INC-2026-118.
 */
public final class CoreTimeoutException extends FallaCoreException {

    public CoreTimeoutException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    @Override
    public boolean puedeHaberseProcesado() {
        return true;
    }
}
