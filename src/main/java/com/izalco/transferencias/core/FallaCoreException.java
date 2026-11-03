package com.izalco.transferencias.core;

/**
 * El core no dio una respuesta de negocio válida.
 * <p>
 * La pregunta clave para cada subtipo es: <b>¿pudo el core haber procesado la transferencia?</b>
 * <ul>
 *   <li>{@link CoreNoDisponibleException}: <b>no</b>. Es seguro reintentar (se libera la llave de idempotencia).</li>
 *   <li>{@link CoreTimeoutException} y {@link RespuestaCoreInvalidaException}: <b>quizá</b>. Reintentar podría
 *       duplicar la transferencia; queda {@code PENDIENTE_CONFIRMACION} y la llave no se libera.</li>
 * </ul>
 */
public abstract sealed class FallaCoreException extends RuntimeException
        permits CoreNoDisponibleException, CoreTimeoutException, RespuestaCoreInvalidaException {

    protected FallaCoreException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    /** {@code true} si el core pudo haber registrado la transferencia (resultado incierto). */
    public abstract boolean puedeHaberseProcesado();
}
