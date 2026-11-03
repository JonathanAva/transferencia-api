package com.izalco.transferencias.core;

/**
 * El core <b>no</b> procesó la solicitud, con certeza:
 * <ul>
 *   <li>respondió el código 91 (core no disponible),</li>
 *   <li>rechazó la conexión, o</li>
 *   <li>no se pudo establecer la conexión dentro del timeout de <b>conexión</b>
 *       (la solicitud nunca salió de la API).</li>
 * </ul>
 * Se traduce a {@code 503 CORE_NO_DISPONIBLE} y la llave de idempotencia se libera.
 */
public final class CoreNoDisponibleException extends FallaCoreException {

    public CoreNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    @Override
    public boolean puedeHaberseProcesado() {
        return false;
    }
}
