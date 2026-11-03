package com.izalco.transferencias.dominio;

/**
 * Estados posibles de una transferencia registrada.
 */
public enum EstadoTransferencia {

    /** El core aprobó la transferencia (código 00). */
    APROBADA,

    /** El core la rechazó por una regla de negocio (códigos 51 y 62). */
    RECHAZADA,

    /**
     * El resultado en el core es desconocido (timeout de lectura o respuesta inválida).
     * Requiere conciliación: el core pudo haberla procesado.
     */
    PENDIENTE_CONFIRMACION
}
