package com.izalco.transferencias.dominio;

import com.izalco.transferencias.TestcontainersConfiguration;
import com.izalco.transferencias.core.ClienteCore;
import com.izalco.transferencias.core.CoreNoDisponibleException;
import com.izalco.transferencias.core.ResultadoCore;
import com.izalco.transferencias.persistencia.EstadoLlave;
import com.izalco.transferencias.persistencia.IdempotenciaEntidad;
import com.izalco.transferencias.persistencia.IdempotenciaRepositorio;
import com.izalco.transferencias.persistencia.TransferenciaRepositorio;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Situaciones raras que no se pueden provocar con un core simulado por HTTP: un bug en medio del proceso
 * o una carrera en la que otra solicitud cierra la llave mientras esta espera al core.
 * Se reemplaza el {@link ClienteCore} por un mock de Mockito ({@code @MockitoBean}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class FallasInesperadasTest {

    @MockitoBean
    private ClienteCore clienteCore;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IdempotenciaRepositorio llaves;

    @Autowired
    private TransferenciaRepositorio transferencias;

    private ResultActions enviar(UUID llave) throws Exception {
        return mvc.perform(post("/transferencias")
                .header("X-Idempotency-Key", llave.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO));
    }

    private static final String CUERPO = """
            {"cuentaOrigen":"100200300","cuentaDestino":"900800700","bancoDestino":"BIZ-0042",
             "monto":150.75,"moneda":"USD","concepto":"Pago de alquiler",
             "fechaSolicitud":"2026-10-01T14:32:10-06:00"}
            """;

    private IdempotenciaEntidad llave(UUID llave) {
        return llaves.findById(llave).orElseThrow();
    }

    /**
     * Un bug después de reservar la llave: 500 sin detalles técnicos, y la llave queda EN_PROCESO
     * (decisión conservadora: no se sabe si la solicitud llegó al core; pasado el umbral se cierra como incierta).
     */
    @Test
    void errorInesperadoDevuelve500SinDetallesTecnicosYNoLiberaLaLlave() throws Exception {
        when(clienteCore.enviar(any())).thenThrow(new IllegalStateException("detalle interno que no debe filtrarse"));
        UUID llave = UUID.randomUUID();

        enviar(llave)
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("ERROR_INTERNO"))
                .andExpect(jsonPath("$.detail").value("Ocurrió un error inesperado. Reporte el correlationId a soporte."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("detalle interno"))));

        assertThat(llave(llave).getEstado()).isEqualTo(EstadoLlave.EN_PROCESO);
    }

    /**
     * Carrera documentada como limitación: mientras esta solicitud espera al core, otra cierra su llave como
     * "atrapada". El resultado real igual se guarda en la transferencia (el GET muestra la verdad), y la respuesta
     * guardada en la llave no se sobrescribe.
     */
    @Test
    void siLaLlaveSeCerroMientrasEsperabaAlCoreLaTransferenciaGuardaIgualElResultadoReal() throws Exception {
        UUID llave = UUID.randomUUID();
        when(clienteCore.enviar(any())).thenAnswer(invocacion -> {
            llaves.completar(llave, 504, "{\"guardada\":\"antes\"}");
            return new ResultadoCore.Aprobada("CORE-TARDE", "APROBADA");
        });

        enviar(llave).andExpect(status().isCreated());

        IdempotenciaEntidad guardada = llave(llave);
        assertThat(guardada.getHttpStatus()).isEqualTo(504);
        assertThat(guardada.getRespuesta()).isEqualTo("{\"guardada\":\"antes\"}");
        assertThat(transferencias.findById(guardada.getTransferenciaId()).orElseThrow().getEstado())
                .isEqualTo(EstadoTransferencia.APROBADA);
    }

    /** Si la llave ya la cerró otra solicitud, un 503 posterior no debe borrar una transferencia que ya tiene dueño. */
    @Test
    void unCoreNoDisponibleNoBorraLaTransferenciaSiLaLlaveYaEstabaCerrada() throws Exception {
        UUID llave = UUID.randomUUID();
        when(clienteCore.enviar(any())).thenAnswer(invocacion -> {
            llaves.completar(llave, 504, "{}");
            throw new CoreNoDisponibleException("conexión rechazada", null);
        });

        enviar(llave).andExpect(status().isServiceUnavailable());

        IdempotenciaEntidad guardada = llave(llave);
        assertThat(guardada.getEstado()).isEqualTo(EstadoLlave.COMPLETADA);
        assertThat(transferencias.existsById(guardada.getTransferenciaId())).isTrue();
    }
}
