package com.izalco.transferencias.persistencia;

/**
 * Estado de una llave de idempotencia.
 */
public enum EstadoLlave {

    /** La primera solicitud con esta llave se está procesando (o el proceso murió a mitad). */
    EN_PROCESO,

    /** Hay una respuesta guardada que se devuelve idéntica en cada reintento. */
    COMPLETADA
}
