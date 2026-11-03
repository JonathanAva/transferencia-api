package com.izalco.transferencias.idempotencia;

import com.izalco.transferencias.api.SolicitudTransferencia;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HashSolicitudTest {

    private static SolicitudTransferencia solicitud(String monto, String concepto, String fecha) {
        return new SolicitudTransferencia("100200300", "900800700", "BIZ-0042",
                new BigDecimal(monto), "USD", concepto, OffsetDateTime.parse(fecha));
    }

    private static final SolicitudTransferencia BASE =
            solicitud("150.75", "Pago de alquiler", "2026-10-01T14:32:10-06:00");

    @Test
    void esUnSha256EnHexadecimal() {
        assertThat(HashSolicitud.calcular(BASE)).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void esDeterminista() {
        assertThat(HashSolicitud.calcular(BASE))
                .isEqualTo(HashSolicitud.calcular(solicitud("150.75", "Pago de alquiler", "2026-10-01T14:32:10-06:00")));
    }

    @Test
    void ignoraDiferenciasDeFormatoDelMonto() {
        assertThat(HashSolicitud.calcular(solicitud("150.70", "Pago de alquiler", "2026-10-01T14:32:10-06:00")))
                .isEqualTo(HashSolicitud.calcular(solicitud("150.7", "Pago de alquiler", "2026-10-01T14:32:10-06:00")));
    }

    @Test
    void ignoraElOffsetSiEsElMismoInstante() {
        assertThat(HashSolicitud.calcular(solicitud("150.75", "Pago de alquiler", "2026-10-01T20:32:10Z")))
                .isEqualTo(HashSolicitud.calcular(BASE));
    }

    @Test
    void cambiaSiCambiaCualquierDatoDeNegocio() {
        assertThat(HashSolicitud.calcular(solicitud("150.76", "Pago de alquiler", "2026-10-01T14:32:10-06:00")))
                .isNotEqualTo(HashSolicitud.calcular(BASE));
        assertThat(HashSolicitud.calcular(solicitud("150.75", "Pago de alquiler.", "2026-10-01T14:32:10-06:00")))
                .isNotEqualTo(HashSolicitud.calcular(BASE));
        assertThat(HashSolicitud.calcular(solicitud("150.75", "Pago de alquiler", "2026-10-01T14:32:11-06:00")))
                .isNotEqualTo(HashSolicitud.calcular(BASE));
    }
}
