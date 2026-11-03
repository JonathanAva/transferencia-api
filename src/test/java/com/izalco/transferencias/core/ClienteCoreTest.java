package com.izalco.transferencias.core;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.BasicCredentials;
import com.github.tomakehurst.wiremock.http.Fault;
import com.izalco.transferencias.api.SolicitudTransferencia;
import com.izalco.transferencias.config.ConfiguracionClienteCore;
import com.izalco.transferencias.config.CoreProperties;
import com.izalco.transferencias.core.ResultadoCore.MotivoRechazo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.OffsetDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToXml;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas del cliente del core contra un core simulado con WireMock (HTTP real, sin Spring).
 */
class ClienteCoreTest {

    private static final String RUTA = "/core/transferencias";
    private static final Duration TIMEOUT_CONEXION = Duration.ofMillis(300);
    private static final Duration TIMEOUT_LECTURA = Duration.ofMillis(800);

    private static WireMockServer core;

    private final SolicitudTransferencia solicitud = new SolicitudTransferencia(
            "100200300", "900800700", "BIZ-0042",
            new BigDecimal("150.75"), "USD", "Pago de alquiler",
            OffsetDateTime.parse("2026-10-01T14:32:10-06:00"));

    @BeforeAll
    static void iniciarCoreSimulado() {
        core = new WireMockServer(options().dynamicPort());
        core.start();
    }

    @AfterAll
    static void detenerCoreSimulado() {
        core.stop();
    }

    @BeforeEach
    void limpiar() {
        core.resetAll();
    }

    private static ClienteCore clienteHacia(String url) {
        CoreProperties propiedades = new CoreProperties(
                url, "usuario-de-prueba", "clave-falsa-solo-pruebas", TIMEOUT_CONEXION, TIMEOUT_LECTURA);
        return new ClienteCore(ConfiguracionClienteCore.crearRestClient(propiedades),
                new MapeadorCore(), new CodecXmlCore(), new TraductorRespuestaCore());
    }

    private ClienteCore cliente() {
        return clienteHacia(core.baseUrl() + RUTA);
    }

    private static void coreResponde(String codigo, String referencia, String mensaje) {
        core.stubFor(post(urlEqualTo(RUTA)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<TransferenciaResponse><Codigo>" + codigo + "</Codigo>"
                        + "<Referencia>" + referencia + "</Referencia>"
                        + "<Mensaje>" + mensaje + "</Mensaje></TransferenciaResponse>")));
    }

    @Nested
    class RespuestasDelCore {

        @Test
        void codigo00DevuelveAprobadaYEnviaElXmlCorrecto() {
            coreResponde("00", "CORE-123456", "APROBADA");

            ResultadoCore resultado = cliente().enviar(solicitud);

            assertThat(resultado).isEqualTo(new ResultadoCore.Aprobada("CORE-123456", "APROBADA"));
            core.verify(1, postRequestedFor(urlEqualTo(RUTA))
                    .withHeader("Content-Type", containing("application/xml"))
                    .withBasicAuth(new BasicCredentials("usuario-de-prueba", "clave-falsa-solo-pruebas"))
                    .withRequestBody(equalToXml("""
                            <TransferenciaRequest>
                              <Origen>100200300</Origen>
                              <Destino>900800700</Destino>
                              <CodigoBanco>BIZ-0042</CodigoBanco>
                              <Importe moneda="USD">150.75</Importe>
                              <Glosa>PAGO DE ALQUILER</Glosa>
                              <FechaHora>20261001203210</FechaHora>
                            </TransferenciaRequest>
                            """)));
        }

        @Test
        void codigo51DevuelveRechazoPorSaldo() {
            coreResponde("51", "CORE-51", "SALDO INSUFICIENTE");

            assertThat(cliente().enviar(solicitud)).isEqualTo(
                    new ResultadoCore.Rechazada(MotivoRechazo.SALDO_INSUFICIENTE, "CORE-51", "SALDO INSUFICIENTE"));
        }

        @Test
        void codigo62DevuelveRechazoPorCuentaBloqueada() {
            coreResponde("62", "CORE-62", "CUENTA BLOQUEADA");

            assertThat(cliente().enviar(solicitud)).isEqualTo(
                    new ResultadoCore.Rechazada(MotivoRechazo.CUENTA_BLOQUEADA, "CORE-62", "CUENTA BLOQUEADA"));
        }

        @Test
        void codigo91EsCoreNoDisponible() {
            coreResponde("91", "", "NO DISPONIBLE");

            assertThatThrownBy(() -> cliente().enviar(solicitud))
                    .isInstanceOfSatisfying(CoreNoDisponibleException.class,
                            e -> assertThat(e.puedeHaberseProcesado()).isFalse());
        }

        @Test
        void codigoDesconocidoEsRespuestaInvalida() {
            coreResponde("99", "", "???");

            assertThatThrownBy(() -> cliente().enviar(solicitud))
                    .isInstanceOf(RespuestaCoreInvalidaException.class);
        }

        @Test
        void xmlMalFormadoEsRespuestaInvalida() {
            core.stubFor(post(urlEqualTo(RUTA)).willReturn(aResponse().withStatus(200).withBody("<html>error")));

            assertThatThrownBy(() -> cliente().enviar(solicitud))
                    .isInstanceOf(RespuestaCoreInvalidaException.class);
        }

        @Test
        void cuerpoVacioEsRespuestaInvalida() {
            core.stubFor(post(urlEqualTo(RUTA)).willReturn(aResponse().withStatus(200)));

            assertThatThrownBy(() -> cliente().enviar(solicitud))
                    .isInstanceOf(RespuestaCoreInvalidaException.class)
                    .hasMessageContaining("sin cuerpo");
        }

        @Test
        void statusHttp500EsRespuestaInvalidaConResultadoIncierto() {
            core.stubFor(post(urlEqualTo(RUTA)).willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> cliente().enviar(solicitud))
                    .isInstanceOfSatisfying(RespuestaCoreInvalidaException.class,
                            e -> assertThat(e.puedeHaberseProcesado()).isTrue())
                    .hasMessageContaining("HTTP 500");
        }
    }

    /**
     * La diferencia clave del incidente INC-2026-118: ¿la solicitud llegó al core o no?
     */
    @Nested
    class TimeoutsYErroresDeRed {

        @Test
        void timeoutDeLecturaEsCoreTimeoutPorqueElCoreSiRecibioLaSolicitud() {
            core.stubFor(post(urlEqualTo(RUTA)).willReturn(aResponse()
                    .withStatus(200)
                    .withBody("<TransferenciaResponse><Codigo>00</Codigo></TransferenciaResponse>")
                    .withFixedDelay((int) TIMEOUT_LECTURA.toMillis() * 3)));

            assertThatThrownBy(() -> cliente().enviar(solicitud))
                    .isInstanceOfSatisfying(CoreTimeoutException.class,
                            e -> assertThat(e.puedeHaberseProcesado()).isTrue());

            // Evidencia: el core SÍ recibió la solicitud (pudo procesarla) y no hubo reintento automático.
            core.verify(1, postRequestedFor(urlEqualTo(RUTA)));
        }

        @Test
        void conexionRechazadaEsCoreNoDisponibleYLaSolicitudNuncaSalio() throws IOException {
            int puertoCerrado;
            try (ServerSocket socket = new ServerSocket(0)) {
                puertoCerrado = socket.getLocalPort();
            } // al cerrar el socket, nadie escucha en ese puerto

            assertThatThrownBy(() -> clienteHacia("http://localhost:" + puertoCerrado + RUTA).enviar(solicitud))
                    .isInstanceOfSatisfying(CoreNoDisponibleException.class,
                            e -> assertThat(e.puedeHaberseProcesado()).isFalse());
        }

        /**
         * 10.255.255.1 es una IP privada no enrutable: el handshake TCP nunca termina y vence el timeout
         * de conexión. En algunas redes el sistema operativo responde "inalcanzable" de inmediato
         * (ConnectException); ambos casos significan que la solicitud no salió, así que el resultado esperado
         * es el mismo. La clasificación exacta de cada excepción se prueba en {@link ClasificacionDeErroresTest}.
         */
        @Test
        void timeoutDeConexionEsCoreNoDisponible() {
            long inicio = System.nanoTime();

            assertThatThrownBy(() -> clienteHacia("http://10.255.255.1:81" + RUTA).enviar(solicitud))
                    .isInstanceOfSatisfying(CoreNoDisponibleException.class,
                            e -> assertThat(e.puedeHaberseProcesado()).isFalse());

            // Venció el timeout de conexión, no el de lectura.
            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(TIMEOUT_LECTURA);
        }

        @Test
        void conexionCortadaAMitadDeLaRespuestaEsResultadoIncierto() {
            core.stubFor(post(urlEqualTo(RUTA)).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

            assertThatThrownBy(() -> cliente().enviar(solicitud))
                    .isInstanceOfSatisfying(RespuestaCoreInvalidaException.class,
                            e -> assertThat(e.puedeHaberseProcesado()).isTrue());

            core.verify(1, postRequestedFor(urlEqualTo(RUTA)));
        }
    }
}
