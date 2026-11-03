package com.izalco.transferencias.api;

import com.izalco.transferencias.TestcontainersConfiguration;
import com.izalco.transferencias.dominio.EstadoTransferencia;
import com.izalco.transferencias.persistencia.TransferenciaEntidad;
import com.izalco.transferencias.persistencia.TransferenciaRepositorio;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /transferencias/{id} y formato de errores (Problem Details), contra PostgreSQL real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ConsultaTransferenciaTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransferenciaRepositorio transferencias;

    @Test
    void devuelveLaTransferenciaConLaFechaTalComoLaEnvioLaApp() throws Exception {
        UUID id = UUID.randomUUID();
        TransferenciaEntidad t = new TransferenciaEntidad(id, "100200300", "900800700", "BIZ-0042",
                new BigDecimal("150.75"), "USD", "Pago de alquiler",
                OffsetDateTime.parse("2026-10-01T14:32:10-06:00"),
                EstadoTransferencia.PENDIENTE_CONFIRMACION, "En proceso", Instant.parse("2026-10-01T20:32:11Z"));
        t.registrarResultado(EstadoTransferencia.APROBADA, "CORE-123456", "APROBADA",
                Instant.parse("2026-10-01T20:32:12Z"));
        transferencias.save(t);

        mvc.perform(get("/transferencias/{id}", id).header("X-Correlation-Id", "prueba-get-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(header().string("X-Correlation-Id", "prueba-get-1"))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.referenciaCore").value("CORE-123456"))
                .andExpect(jsonPath("$.monto").value(150.75))
                .andExpect(jsonPath("$.concepto").value("Pago de alquiler"))
                .andExpect(jsonPath("$.fechaSolicitud").value("2026-10-01T14:32:10-06:00"))
                .andExpect(jsonPath("$.fechaRegistro").value("2026-10-01T20:32:11Z"));
    }

    @Test
    void idInexistenteDevuelve404EnFormatoProblemDetails() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(get("/transferencias/{id}", id).header("X-Correlation-Id", "prueba-404"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://api.izalco-digital.example/problemas/transferencia-no-encontrada"))
                .andExpect(jsonPath("$.title").value("Transferencia no encontrada"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("No existe una transferencia con id " + id + "."))
                .andExpect(jsonPath("$.instance").value("/transferencias/" + id))
                .andExpect(jsonPath("$.codigo").value("TRANSFERENCIA_NO_ENCONTRADA"))
                .andExpect(jsonPath("$.correlationId").value("prueba-404"));
    }

    @Test
    void idQueNoEsUuidDevuelve400ConElCampo() throws Exception {
        mvc.perform(get("/transferencias/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
                .andExpect(jsonPath("$.errores[0].campo").value("id"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("debe ser un UUID válido"))
                .andExpect(jsonPath("$.correlationId").value(matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void losErroresPropiosDeSpringTambienTienenCodigoYCorrelationId() throws Exception {
        // PUT no está definido en el contrato: 405 Method Not Allowed
        mvc.perform(put("/transferencias/{id}", UUID.randomUUID()).header("X-Correlation-Id", "prueba-405"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
                .andExpect(jsonPath("$.correlationId").value("prueba-405"))
                .andExpect(jsonPath("$.type").value("https://api.izalco-digital.example/problemas/solicitud-invalida"));
    }

    @Test
    void rutaInexistenteDevuelve404ConCodigo() throws Exception {
        mvc.perform(get("/no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
                .andExpect(jsonPath("$.correlationId").exists());
    }
}
