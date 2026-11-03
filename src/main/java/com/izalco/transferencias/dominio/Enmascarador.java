package com.izalco.transferencias.dominio;

/**
 * Oculta datos sensibles antes de escribirlos en los logs. Los logs se copian a muchas herramientas
 * (agregadores, tickets, correos) con controles de acceso más débiles que la base de datos.
 */
public final class Enmascarador {

    private static final int DIGITOS_VISIBLES = 4;

    private Enmascarador() {
    }

    /**
     * Deja visibles solo los últimos 4 caracteres: {@code 100200300} → {@code *****0300}. Suficiente para que
     * soporte identifique la cuenta con el cliente, inútil para quien lea el log sin autorización.
     * Si la cuenta tiene 4 caracteres o menos, se oculta completa (mostrarla sería mostrarla entera).
     */
    public static String cuenta(String cuenta) {
        if (cuenta == null || cuenta.isEmpty()) {
            return "****";
        }
        if (cuenta.length() <= DIGITOS_VISIBLES) {
            return "*".repeat(cuenta.length());
        }
        int ocultos = cuenta.length() - DIGITOS_VISIBLES;
        return "*".repeat(ocultos) + cuenta.substring(ocultos);
    }
}
