package com.izalco.transferencias.core;

/**
 * El core respondió algo que no se puede interpretar: código desconocido, XML mal formado,
 * status HTTP distinto de 2xx o conexión cortada a mitad de la respuesta.
 * Como la solicitud sí llegó al core, el resultado es incierto.
 * <p>
 * Se traduce a {@code 502 RESPUESTA_CORE_INVALIDA} y la transferencia queda {@code PENDIENTE_CONFIRMACION}.
 */
public final class RespuestaCoreInvalidaException extends FallaCoreException {

    public RespuestaCoreInvalidaException(String mensaje) {
        super(mensaje, null);
    }

    public RespuestaCoreInvalidaException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    @Override
    public boolean puedeHaberseProcesado() {
        return true;
    }
}
