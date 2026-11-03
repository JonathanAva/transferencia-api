package com.izalco.transferencias.core;

import com.izalco.transferencias.api.SolicitudTransferencia;
import com.izalco.transferencias.core.xml.SolicitudCore;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Traduce la solicitud JSON de la app al formato XML del core legado.
 * Es la "capa anticorrupción": las reglas raras del core (UTC, mayúsculas, 30 caracteres)
 * quedan encerradas aquí y no contaminan el resto de la aplicación.
 */
@Component
public class MapeadorCore {

    /** Longitud máxima de la glosa que acepta el core. */
    static final int LONGITUD_MAXIMA_GLOSA = 30;

    /** Glosa usada si el concepto queda vacío tras quitar caracteres no ASCII (por ejemplo, solo emojis). */
    static final String GLOSA_POR_DEFECTO = "TRANSFERENCIA";

    /**
     * Formato del core: año, mes, día, hora, minuto, segundo.
     * {@code HH} es la hora de 0 a 23. Con {@code hh} (1 a 12, sin AM/PM) las 20:32 se escribirían
     * "08:32": una de las causas posibles del bug de hora del incidente INC-2026-118.
     */
    private static final DateTimeFormatter FORMATO_CORE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);

    public SolicitudCore aCore(SolicitudTransferencia solicitud) {
        return new SolicitudCore(
                solicitud.cuentaOrigen(),
                solicitud.cuentaDestino(),
                solicitud.bancoDestino(),
                new SolicitudCore.Importe(solicitud.moneda(), formatearMonto(solicitud.monto())),
                normalizarGlosa(solicitud.concepto()),
                formatearFechaUtc(solicitud.fechaSolicitud()));
    }

    /**
     * Convierte la fecha a UTC conservando el mismo instante y la formatea para el core.
     * Ejemplo: {@code 2026-10-01T14:32:10-06:00} → {@code 20261001203210}.
     * {@code withOffsetSameInstant} suma las 6 horas del offset (14:32 − (−06:00) = 20:32);
     * restarlas daría 08:32, la otra causa posible del bug del incidente.
     */
    static String formatearFechaUtc(OffsetDateTime fecha) {
        return fecha.withOffsetSameInstant(ZoneOffset.UTC).format(FORMATO_CORE);
    }

    /**
     * Prepara la glosa para el core:
     * <ol>
     *   <li>quita tildes y diéresis ("Á" → "A", "ñ" → "n"),</li>
     *   <li>elimina cualquier otro carácter fuera de ASCII imprimible (emojis, controles),</li>
     *   <li>junta espacios repetidos y recorta los extremos,</li>
     *   <li>pasa a MAYÚSCULAS con {@link Locale#ROOT} (independiente del idioma del servidor),</li>
     *   <li>trunca a 30 caracteres.</li>
     * </ol>
     */
    static String normalizarGlosa(String concepto) {
        String sinTildes = Normalizer.normalize(concepto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String soloAscii = sinTildes.replaceAll("[^\\x20-\\x7E]", " ");
        String limpio = soloAscii.replaceAll("\\s+", " ").strip().toUpperCase(Locale.ROOT);

        if (limpio.isEmpty()) {
            return GLOSA_POR_DEFECTO;
        }
        if (limpio.length() > LONGITUD_MAXIMA_GLOSA) {
            limpio = limpio.substring(0, LONGITUD_MAXIMA_GLOSA).strip();
        }
        return limpio;
    }

    /**
     * Formatea el monto con exactamente 2 decimales y sin notación científica ("150.75", "100.00").
     * {@code RoundingMode.UNNECESSARY} lanza una excepción si hubiera que redondear: con dinero
     * es preferible fallar a redondear en silencio (la validación ya garantiza máximo 2 decimales).
     */
    static String formatearMonto(BigDecimal monto) {
        return monto.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
