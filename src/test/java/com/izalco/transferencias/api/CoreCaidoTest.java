package com.izalco.transferencias.api;

import com.izalco.transferencias.TestcontainersConfiguration;
import com.izalco.transferencias.persistencia.IdempotenciaRepositorio;
import com.izalco.transferencias.persistencia.TransferenciaRepositorio;
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
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Caída total del core, de punta a punta: la API apunta a un puerto donde nadie escucha
 * (conexión rechazada). La solicitud nunca llega al core, así que debe responder 503,
 * no guardar nada y permitir reintentar con la misma llave.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CoreCaidoTest {

    private static final int PUERTO_SIN_CORE = puertoLibre();

    @DynamicPropertySource
    static void apuntarAUnCoreApagado(DynamicPropertyRegistry registro) {
        registro.add("core.url", () -> "http://localhost:" + PUERTO_SIN_CORE + "/core/transferencias");
        registro.add("core.timeout-conexion", () -> "300ms");
        registro.add("core.timeout-lectura", () -> "1s");
    }

    /** Abre un puerto libre y lo cierra: queda un puerto en el que nadie escucha. */
    private static int puertoLibre() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransferenciaRepositorio transferencias;

    @Autowired
    private IdempotenciaRepositorio llaves;

    private ResultActions enviar(UUID llave) throws Exception {
        return mvc.perform(post("/transferencias")
                .header("X-Idempotency-Key", llave.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(RegistroTransferenciaTest.CUERPO));
    }

    @Test
    void conElCoreCaidoDevuelve503NoGuardaNadaYPermiteReintentarConLaMismaLlave() throws Exception {
        UUID llave = UUID.randomUUID();

        enviar(llave)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.codigo").value("CORE_NO_DISPONIBLE"))
                .andExpect(jsonPath("$.transferenciaId").doesNotExist())
                .andExpect(jsonPath("$.correlationId").exists());

        assertThat(llaves.existsById(llave)).as("la llave se libera").isFalse();
        assertThat(transferencias.count()).as("no queda ninguna transferencia guardada").isZero();

        // El reintento con la MISMA llave vuelve a intentar (no es 409 ni una respuesta repetida).
        enviar(llave)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(transferencias.count()).isZero();
    }
}
