package com.izalco.transferencias.config;

import com.izalco.transferencias.TestcontainersConfiguration;
import com.izalco.transferencias.api.SolicitudTransferencia;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica las reglas de JSON sobre el {@link JsonMapper} real que usa Spring MVC.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ConfiguracionJacksonTest {

    private static final String SOLICITUD = """
            {"cuentaOrigen":"100200300","cuentaDestino":"900800700","bancoDestino":"BIZ-0042",
             "monto":150.75,"moneda":"USD","concepto":"Pago de alquiler",
             "fechaSolicitud":"2026-10-01T14:32:10-06:00"%s}
            """;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void conservaElOffsetOriginalDeLaFecha() {
        SolicitudTransferencia s = jsonMapper.readValue(SOLICITUD.formatted(""), SolicitudTransferencia.class);

        assertThat(s.fechaSolicitud().toString()).isEqualTo("2026-10-01T14:32:10-06:00");
    }

    @Test
    void leeElMontoComoBigDecimalExacto() {
        SolicitudTransferencia s = jsonMapper.readValue(SOLICITUD.formatted(""), SolicitudTransferencia.class);

        assertThat(s.monto()).isEqualTo(new BigDecimal("150.75"));
    }

    @Test
    void rechazaCamposDesconocidos() {
        assertThatThrownBy(() -> jsonMapper.readValue(SOLICITUD.formatted(",\"montto\":1"), SolicitudTransferencia.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("montto");
    }
}
