package com.izalco.transferencias.core;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica, excepción por excepción, cómo se clasifica un error de red del cliente HTTP.
 * Spring envuelve los errores de E/S en {@link ResourceAccessException}.
 */
class ClasificacionDeErroresTest {

    private static FallaCoreException clasificar(IOException causa) {
        return ClienteCore.clasificarErrorDeRed(new ResourceAccessException("error de E/S", causa));
    }

    /**
     * HttpConnectTimeoutException ES una HttpTimeoutException (subclase). Si el código preguntara primero
     * por HttpTimeoutException, un timeout de conexión se trataría como incierto y bloquearía el reintento.
     */
    @Test
    void timeoutDeConexionEsCoreNoDisponibleAunqueSeaSubclaseDeHttpTimeoutException() {
        HttpConnectTimeoutException timeoutConexion = new HttpConnectTimeoutException("HTTP connect timed out");
        assertThat(timeoutConexion).isInstanceOf(HttpTimeoutException.class);

        assertThat(clasificar(timeoutConexion))
                .isInstanceOf(CoreNoDisponibleException.class)
                .satisfies(e -> assertThat(e.puedeHaberseProcesado()).isFalse());
    }

    @Test
    void conexionRechazadaEsCoreNoDisponible() {
        assertThat(clasificar(new ConnectException("Connection refused")))
                .isInstanceOf(CoreNoDisponibleException.class);
    }

    @Test
    void conexionRechazadaEnvueltaTambienSeDetecta() {
        assertThat(clasificar(new IOException("fallo", new ConnectException("Connection refused"))))
                .isInstanceOf(CoreNoDisponibleException.class);
    }

    @Test
    void timeoutDeLecturaEsCoreTimeout() {
        assertThat(clasificar(new HttpTimeoutException("request timed out")))
                .isInstanceOf(CoreTimeoutException.class)
                .satisfies(e -> assertThat(e.puedeHaberseProcesado()).isTrue());
    }

    @Test
    void otroErrorDeEntradaSalidaEsRespuestaInvalidaConResultadoIncierto() {
        assertThat(clasificar(new IOException("Connection reset")))
                .isInstanceOf(RespuestaCoreInvalidaException.class)
                .satisfies(e -> assertThat(e.puedeHaberseProcesado()).isTrue());
    }
}
