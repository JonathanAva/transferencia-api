package com.izalco.transferencias.api;

import com.izalco.transferencias.TestcontainersConfiguration;
import com.izalco.transferencias.core.ClienteCore;
import com.izalco.transferencias.core.ResultadoCore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo que queda escrito en los logs: el correlationId en cada línea y los números de cuenta enmascarados.
 * {@link OutputCaptureExtension} captura lo que la aplicación escribe en consola durante la prueba.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LogsTest {

    @MockitoBean
    private ClienteCore clienteCore;

    @Autowired
    private MockMvc mvc;

    @Test
    void elLogDelResultadoLlevaElCorrelationIdYLasCuentasEnmascaradas(CapturedOutput salida) throws Exception {
        when(clienteCore.enviar(any())).thenReturn(new ResultadoCore.Aprobada("CORE-LOG-1", "APROBADA"));

        mvc.perform(post("/transferencias")
                        .header("X-Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Correlation-Id", "prueba-logs-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RegistroTransferenciaTest.CUERPO))
                .andExpect(status().isCreated());

        String lineaDelResultado = salida.getOut().lines()
                .filter(linea -> linea.contains("referenciaCore=CORE-LOG-1"))
                .findFirst().orElseThrow(() -> new AssertionError("no se registró el resultado de la transferencia"));

        assertThat(lineaDelResultado)
                .contains("[prueba-logs-123]")
                .contains("origen=*****0300")
                .contains("destino=*****0700")
                .contains("APROBADA");
        assertThat(salida.getAll())
                .as("ningún log contiene los números de cuenta completos")
                .doesNotContain("100200300")
                .doesNotContain("900800700")
                .as("el concepto (texto libre) no se registra")
                .doesNotContain("Pago de alquiler");
    }
}
