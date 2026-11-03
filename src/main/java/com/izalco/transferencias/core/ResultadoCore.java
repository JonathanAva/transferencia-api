package com.izalco.transferencias.core;

/**
 * Respuesta definitiva del core, ya traducida a términos de negocio.
 * <p>
 * Es una interfaz {@code sealed}: solo existen estas dos variantes, así que un {@code switch}
 * sobre ella obliga al compilador a verificar que se manejen ambas.
 * Los casos sin respuesta definitiva (core caído, timeout, respuesta inválida) no son un
 * resultado sino una {@link FallaCoreException}.
 */
public sealed interface ResultadoCore {

    /** Código 00. */
    record Aprobada(String referencia, String mensaje) implements ResultadoCore {
    }

    /** Códigos 51 y 62: el core procesó la solicitud y la rechazó por una regla de negocio. */
    record Rechazada(MotivoRechazo motivo, String referencia, String mensaje) implements ResultadoCore {
    }

    enum MotivoRechazo {
        SALDO_INSUFICIENTE,
        CUENTA_BLOQUEADA
    }
}
