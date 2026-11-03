package com.izalco.transferencias.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.izalco.transferencias.TestcontainersConfiguration;
import com.izalco.transferencias.dominio.EstadoTransferencia;
import com.izalco.transferencias.idempotencia.HashSolicitud;
import com.izalco.transferencias.persistencia.EstadoLlave;
import com.izalco.transferencias.persistencia.IdempotenciaRepositorio;
import com.izalco.transferencias.persistencia.TransferenciaEntidad;
import com.izalco.transferencias.persistencia.TransferenciaRepositorio;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /transferencias de punta a punta: API real + PostgreSQL real (Testcontainers) + core simulado (WireMock).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RegistroTransferenciaTest {

    private static final String RUTA_CORE = "/core/transferencias";
    private static final Duration TIMEOUT_LECTURA = Duration.ofSeconds(1);
    private static final Duration MARGEN = Duration.ofSeconds(1);

    private static final WireMockServer core = new WireMockServer(options().dynamicPort());

    static {
        core.start();
    }

    @DynamicPropertySource
    static void apuntarAlCoreSimulado(DynamicPropertyRegistry registro) {
        registro.add("core.url", () -> core.baseUrl() + RUTA_CORE);
        registro.add("core.timeout-conexion", () -> "300ms");
        registro.add("core.timeout-lectura", () -> TIMEOUT_LECTURA.toMillis() + "ms");
        registro.add("idempotencia.margen-en-proceso", () -> MARGEN.toMillis() + "ms");
    }

    @AfterAll
    static void detenerCore() {
        core.stop();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private TransferenciaRepositorio transferencias;

    @Autowired
    private IdempotenciaRepositorio llaves;

    @BeforeEach
    void limpiarCore() {
        core.resetAll();
    }

    // ---------- Auxiliares ----------

    static final String CUERPO = """
            {
              "cuentaOrigen": "100200300",
              "cuentaDestino": "900800700",
              "bancoDestino": "BIZ-0042",
              "monto": 150.75,
              "moneda": "USD",
              "concepto": "Pago de alquiler",
              "fechaSolicitud": "2026-10-01T14:32:10-06:00"
            }
            """;

    static final SolicitudTransferencia SOLICITUD = new SolicitudTransferencia("100200300", "900800700",
            "BIZ-0042", new BigDecimal("150.75"), "USD", "Pago de alquiler",
            OffsetDateTime.parse("2026-10-01T14:32:10-06:00"));

    private ResultActions enviar(UUID llave, String cuerpo) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/transferencias")
                .header("X-Idempotency-Key", llave.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo));
    }

    private static void coreResponde(String codigo) {
        core.stubFor(post(urlEqualTo(RUTA_CORE)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<TransferenciaResponse><Codigo>" + codigo + "</Codigo>"
                        + "<Referencia>CORE-" + codigo + "</Referencia><Mensaje>X</Mensaje></TransferenciaResponse>")));
    }

    private static int llamadasAlCore() {
        return core.findAll(postRequestedFor(urlEqualTo(RUTA_CORE))).size();
    }

    private UUID transferenciaIdDe(MvcResult resultado) throws Exception {
        JsonNode cuerpo = json.readTree(resultado.getResponse().getContentAsString());
        return UUID.fromString(cuerpo.has("transferenciaId")
                ? cuerpo.get("transferenciaId").asString() : cuerpo.get("id").asString());
    }

    private EstadoTransferencia estadoEnBase(UUID id) {
        return transferencias.findById(id).orElseThrow().getEstado();
    }

    // ---------- Respuestas del core ----------

    @Nested
    class RespuestasDelCore {

        @Test
        void aprobadaDevuelve201ConLocationYSeGuarda() throws Exception {
            coreResponde("00");

            MvcResult r = enviar(UUID.randomUUID(), CUERPO)
                    .andExpect(status().isCreated())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andExpect(header().doesNotExist("Idempotent-Replayed"))
                    .andExpect(jsonPath("$.estado").value("APROBADA"))
                    .andExpect(jsonPath("$.referenciaCore").value("CORE-00"))
                    .andExpect(jsonPath("$.mensaje").value("Transferencia aprobada"))
                    .andExpect(jsonPath("$.fechaRegistro").exists())
                    .andReturn();

            UUID id = transferenciaIdDe(r);
            assertThat(r.getResponse().getHeader("Location")).isEqualTo("/transferencias/" + id);
            mvc.perform(get("/transferencias/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.estado").value("APROBADA"))
                    .andExpect(jsonPath("$.fechaSolicitud").value("2026-10-01T14:32:10-06:00"));
        }

        @Test
        void saldoInsuficienteDevuelve422YQuedaRechazada() throws Exception {
            coreResponde("51");

            MvcResult r = enviar(UUID.randomUUID(), CUERPO)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.codigo").value("SALDO_INSUFICIENTE"))
                    .andExpect(jsonPath("$.transferenciaId").exists())
                    .andExpect(jsonPath("$.correlationId").exists())
                    .andReturn();

            assertThat(estadoEnBase(transferenciaIdDe(r))).isEqualTo(EstadoTransferencia.RECHAZADA);
        }

        @Test
        void cuentaBloqueadaDevuelve422() throws Exception {
            coreResponde("62");

            enviar(UUID.randomUUID(), CUERPO)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.codigo").value("CUENTA_BLOQUEADA"));
        }

        @Test
        void codigoDesconocidoDevuelve502YQuedaPendiente() throws Exception {
            coreResponde("99");

            MvcResult r = enviar(UUID.randomUUID(), CUERPO)
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.codigo").value("RESPUESTA_CORE_INVALIDA"))
                    .andReturn();

            assertThat(estadoEnBase(transferenciaIdDe(r))).isEqualTo(EstadoTransferencia.PENDIENTE_CONFIRMACION);
        }
    }

    // ---------- Idempotencia ----------

    @Nested
    class Idempotencia {

        @Test
        void reintentoConLaMismaLlaveDevuelveLaMismaRespuestaSinLlamarAlCore() throws Exception {
            coreResponde("00");
            UUID llave = UUID.randomUUID();

            String primera = enviar(llave, CUERPO).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            MvcResult segunda = enviar(llave, CUERPO)
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andReturn();

            assertThat(segunda.getResponse().getContentAsString()).isEqualTo(primera);
            assertThat(segunda.getResponse().getHeader("Location")).isEqualTo("/transferencias/" + transferenciaIdDe(segunda));
            assertThat(llamadasAlCore()).isEqualTo(1);
        }

        @Test
        void mismoContenidoConOtroFormatoJsonEsUnReintentoValido() throws Exception {
            coreResponde("00");
            UUID llave = UUID.randomUUID();
            enviar(llave, CUERPO).andExpect(status().isCreated());

            String mismoContenido = """
                    {"fechaSolicitud":"2026-10-01T14:32:10-06:00","monto":150.750,"moneda":"USD",
                     "concepto":"Pago de alquiler","bancoDestino":"BIZ-0042",
                     "cuentaDestino":"900800700","cuentaOrigen":"100200300"}""";

            enviar(llave, mismoContenido)
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "true"));
            assertThat(llamadasAlCore()).isEqualTo(1);
        }

        @Test
        void mismaLlaveConOtroCuerpoDevuelve422() throws Exception {
            coreResponde("00");
            UUID llave = UUID.randomUUID();
            enviar(llave, CUERPO).andExpect(status().isCreated());

            enviar(llave, CUERPO.replace("150.75", "999.99"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.codigo").value("IDEMPOTENCY_KEY_REUTILIZADA"))
                    .andExpect(jsonPath("$.transferenciaId").doesNotExist());
            assertThat(llamadasAlCore()).isEqualTo(1);
        }

        @Test
        void unRechazoTambienSeRepiteIgual() throws Exception {
            coreResponde("51");
            UUID llave = UUID.randomUUID();
            String primera = enviar(llave, CUERPO).andReturn().getResponse().getContentAsString();

            enviar(llave, CUERPO)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(content().string(primera));
            assertThat(llamadasAlCore()).isEqualTo(1);
        }

        @Test
        void llaveVencidaSeTrataComoNueva() throws Exception {
            coreResponde("00");
            UUID llave = UUID.randomUUID();
            UUID anterior = guardarTransferencia();
            Instant hace25h = Instant.now().minus(Duration.ofHours(25));
            llaves.reservar(llave, HashSolicitud.calcular(SOLICITUD), anterior, hace25h, hace25h.plus(Duration.ofHours(24)));
            llaves.completar(llave, 201, "{}");

            MvcResult r = enviar(llave, CUERPO)
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Idempotent-Replayed"))
                    .andReturn();

            assertThat(transferenciaIdDe(r)).isNotEqualTo(anterior);
            assertThat(llamadasAlCore()).isEqualTo(1);
        }

        @Test
        void dosSolicitudesSimultaneasConLaMismaLlaveSoloLlamanUnaVezAlCore() throws Exception {
            core.stubFor(post(urlEqualTo(RUTA_CORE)).willReturn(aResponse().withStatus(200)
                    .withBody("<TransferenciaResponse><Codigo>00</Codigo></TransferenciaResponse>")
                    .withFixedDelay(500)));
            UUID llave = UUID.randomUUID();
            Callable<Integer> solicitud = () -> enviar(llave, CUERPO).andReturn().getResponse().getStatus();

            List<Integer> statuses;
            try (ExecutorService hilos = Executors.newFixedThreadPool(2)) {
                List<Future<Integer>> futuros = hilos.invokeAll(List.of(solicitud, solicitud));
                statuses = List.of(futuros.get(0).get(), futuros.get(1).get());
            }

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(llamadasAlCore()).isEqualTo(1);
        }
    }

    // ---------- Caída y timeouts del core ----------

    @Nested
    class CaidaYTimeoutsDelCore {

        @Test
        void coreNoDisponibleDevuelve503YLiberaLaLlaveParaReintentar() throws Exception {
            coreResponde("91");
            UUID llave = UUID.randomUUID();

            enviar(llave, CUERPO)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.codigo").value("CORE_NO_DISPONIBLE"))
                    .andExpect(jsonPath("$.transferenciaId").doesNotExist());
            assertThat(llaves.existsById(llave)).isFalse();

            // El core se recupera: el reintento con la MISMA llave sí llega al core.
            core.resetAll();
            coreResponde("00");
            enviar(llave, CUERPO)
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Idempotent-Replayed"));
            assertThat(llamadasAlCore()).isEqualTo(1);
        }

        @Test
        void timeoutDeLecturaDevuelve504QuedaPendienteYNoSeVuelveALlamarAlCore() throws Exception {
            core.stubFor(post(urlEqualTo(RUTA_CORE)).willReturn(aResponse().withStatus(200)
                    .withBody("<TransferenciaResponse><Codigo>00</Codigo></TransferenciaResponse>")
                    .withFixedDelay((int) TIMEOUT_LECTURA.multipliedBy(3).toMillis())));
            UUID llave = UUID.randomUUID();

            MvcResult r = enviar(llave, CUERPO)
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.codigo").value("CORE_TIMEOUT"))
                    .andExpect(jsonPath("$.transferenciaId").exists())
                    .andReturn();
            UUID id = transferenciaIdDe(r);
            assertThat(estadoEnBase(id)).isEqualTo(EstadoTransferencia.PENDIENTE_CONFIRMACION);

            // La app reintenta (como en el incidente): recibe la respuesta guardada, sin nueva llamada al core.
            enviar(llave, CUERPO)
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(header().string("Idempotent-Replayed", "true"));
            assertThat(llamadasAlCore()).isEqualTo(1);
        }
    }

    // ---------- Llaves EN_PROCESO ----------

    @Nested
    class LlavesEnProceso {

        @Test
        void llaveEnProcesoRecienteDevuelve409SinLlamarAlCore() throws Exception {
            UUID llave = UUID.randomUUID();
            Instant ahora = Instant.now();
            llaves.reservar(llave, HashSolicitud.calcular(SOLICITUD), guardarTransferencia(), ahora, ahora.plus(Duration.ofHours(24)));

            enviar(llave, CUERPO)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigo").value("SOLICITUD_EN_PROCESO"));
            assertThat(llamadasAlCore()).isZero();
        }

        /**
         * Simula un pod que murió a mitad de la llamada al core: la llave quedó EN_PROCESO hace más que
         * timeout de lectura + margen. No debe devolver 409 para siempre ni liberarse: se cierra como incierta.
         */
        @Test
        void llaveAtrapadaSeCierraComoResultadoInciertoSinLiberarlaNiLlamarAlCore() throws Exception {
            UUID llave = UUID.randomUUID();
            UUID id = guardarTransferencia();
            Instant hace = Instant.now().minus(TIMEOUT_LECTURA.plus(MARGEN).plusSeconds(5));
            llaves.reservar(llave, HashSolicitud.calcular(SOLICITUD), id, hace, hace.plus(Duration.ofHours(24)));

            enviar(llave, CUERPO)
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.codigo").value("CORE_TIMEOUT"))
                    .andExpect(jsonPath("$.transferenciaId").value(id.toString()))
                    .andExpect(header().doesNotExist("Idempotent-Replayed"));

            assertThat(llamadasAlCore()).isZero();
            assertThat(llaves.findById(llave).orElseThrow().getEstado()).isEqualTo(EstadoLlave.COMPLETADA);
            assertThat(estadoEnBase(id)).isEqualTo(EstadoTransferencia.PENDIENTE_CONFIRMACION);

            // Los siguientes reintentos reciben la misma respuesta guardada.
            enviar(llave, CUERPO)
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(header().string("Idempotent-Replayed", "true"));
            assertThat(llamadasAlCore()).isZero();
        }

        private UUID guardarTransferencia() {
            return RegistroTransferenciaTest.this.guardarTransferencia();
        }
    }

    private UUID guardarTransferencia() {
        UUID id = UUID.randomUUID();
        transferencias.save(new TransferenciaEntidad(id, SOLICITUD.cuentaOrigen(), SOLICITUD.cuentaDestino(),
                SOLICITUD.bancoDestino(), SOLICITUD.monto(), SOLICITUD.moneda(), SOLICITUD.concepto(),
                SOLICITUD.fechaSolicitud(), EstadoTransferencia.PENDIENTE_CONFIRMACION, "En proceso",
                Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        return id;
    }

    // ---------- Validaciones ----------

    @Nested
    class Validaciones {

        @Test
        void sinLlaveDevuelve400AunqueElCuerpoTambienSeaInvalido() throws Exception {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/transferencias")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("IDEMPOTENCY_KEY_REQUERIDA"));
        }

        @Test
        void llaveQueNoEsUuidDevuelve400() throws Exception {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/transferencias")
                            .header("X-Idempotency-Key", "no-es-uuid")
                            .contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("IDEMPOTENCY_KEY_REQUERIDA"));
        }

        @Test
        void cuerpoInvalidoDevuelve400ConCadaCampo() throws Exception {
            enviar(UUID.randomUUID(), CUERPO.replace("150.75", "-5").replace("\"USD\"", "\"usd\""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
                    .andExpect(jsonPath("$.errores[0].campo").value("moneda"))
                    .andExpect(jsonPath("$.errores[1].campo").value("monto"));
            assertThat(llamadasAlCore()).isZero();
        }

        @Test
        void montoConTresDecimalesSignificativosDevuelve400() throws Exception {
            enviar(UUID.randomUUID(), CUERPO.replace("150.75", "150.755"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errores[0].campo").value("monto"))
                    .andExpect(jsonPath("$.errores[0].mensaje").value("debe tener máximo 13 enteros y 2 decimales"));
            assertThat(llamadasAlCore()).isZero();
        }

        @Test
        void montoConCerosALaDerechaSeAceptaYSeGuardaNormalizado() throws Exception {
            coreResponde("00");

            MvcResult r = enviar(UUID.randomUUID(), CUERPO.replace("150.75", "150.750"))
                    .andExpect(status().isCreated())
                    .andReturn();

            assertThat(transferencias.findById(transferenciaIdDe(r)).orElseThrow().getMonto())
                    .isEqualByComparingTo("150.75");
            core.verify(postRequestedFor(urlEqualTo(RUTA_CORE))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing(
                            "<Importe moneda=\"USD\">150.75</Importe>")));
        }

        @Test
        void fechaSinZonaHorariaDevuelve400() throws Exception {
            enviar(UUID.randomUUID(), CUERPO.replace("2026-10-01T14:32:10-06:00", "2026-10-01T14:32:10"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
                    .andExpect(jsonPath("$.errores[0].campo").value("fechaSolicitud"));
        }

        @Test
        void campoDesconocidoDevuelve400() throws Exception {
            enviar(UUID.randomUUID(), CUERPO.replace("\"moneda\"", "\"montto\": 1, \"moneda\""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errores[0].campo").value("montto"));
        }

        @Test
        void jsonMalFormadoDevuelve400() throws Exception {
            enviar(UUID.randomUUID(), "{\"cuentaOrigen\": ")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
        }
    }
}
