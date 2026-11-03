package com.izalco.transferencias.idempotencia;

import com.izalco.transferencias.api.SolicitudTransferencia;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Huella SHA-256 del <b>contenido</b> de una solicitud, para detectar una llave reutilizada con otro cuerpo.
 * <p>
 * Se calcula sobre los valores ya interpretados, no sobre el texto JSON: así el orden de los campos,
 * los espacios o escribir {@code 150.70} en vez de {@code 150.7} no cambian la huella, y un reintento
 * legítimo de la app no se rechaza por una diferencia de formato.
 */
public final class HashSolicitud {

    /** Separador que no puede aparecer en los campos (carácter de control "unit separator"). */
    private static final String SEPARADOR = "\u001F";

    private HashSolicitud() {
    }

    public static String calcular(SolicitudTransferencia s) {
        String canonico = String.join(SEPARADOR,
                s.cuentaOrigen(),
                s.cuentaDestino(),
                s.bancoDestino(),
                s.monto().stripTrailingZeros().toPlainString(),
                s.moneda(),
                s.concepto(),
                s.fechaSolicitud().toInstant().toString());
        return sha256(canonico);
    }

    private static String sha256(String texto) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Toda JVM está obligada a incluir SHA-256: esto no puede ocurrir.
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
