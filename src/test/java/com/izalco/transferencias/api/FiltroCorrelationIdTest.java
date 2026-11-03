package com.izalco.transferencias.api;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FiltroCorrelationIdTest {

    private final FiltroCorrelationId filtro = new FiltroCorrelationId();
    private final MockHttpServletRequest solicitud = new MockHttpServletRequest("GET", "/transferencias/x");
    private final MockHttpServletResponse respuesta = new MockHttpServletResponse();

    /** Ejecuta el filtro y devuelve el correlation id que vio el resto de la aplicación (el MDC). */
    private String filtrar() throws Exception {
        AtomicReference<String> vistoDentro = new AtomicReference<>();
        filtro.doFilter(solicitud, respuesta, (req, res) -> vistoDentro.set(FiltroCorrelationId.actual()));
        return vistoDentro.get();
    }

    @Test
    void reutilizaElCorrelationIdRecibido() throws Exception {
        solicitud.addHeader("X-Correlation-Id", "app-movil.123_abc-XYZ");

        assertThat(filtrar()).isEqualTo("app-movil.123_abc-XYZ");
        assertThat(respuesta.getHeader("X-Correlation-Id")).isEqualTo("app-movil.123_abc-XYZ");
    }

    @Test
    void generaUnoSiNoLlega() throws Exception {
        String generado = filtrar();

        assertThat(generado).matches("[0-9a-f-]{36}");
        assertThat(respuesta.getHeader("X-Correlation-Id")).isEqualTo(generado);
    }

    @Test
    void reemplazaUnValorQueIntentaInyectarLineasEnLosLogs() throws Exception {
        solicitud.addHeader("X-Correlation-Id", "abc\nERROR falso en el log");

        String usado = filtrar();

        assertThat(usado).doesNotContain("\n").matches("[0-9a-f-]{36}");
    }

    @Test
    void reemplazaUnValorDemasiadoLargo() throws Exception {
        solicitud.addHeader("X-Correlation-Id", "a".repeat(65));

        assertThat(filtrar()).hasSize(36);
    }

    @Test
    void limpiaElMdcAlTerminar() throws Exception {
        solicitud.addHeader("X-Correlation-Id", "id-de-prueba");

        filtrar();

        assertThat(MDC.get(FiltroCorrelationId.CLAVE_MDC)).isNull();
        assertThat(FiltroCorrelationId.actual()).isEqualTo("desconocido");
    }
}
