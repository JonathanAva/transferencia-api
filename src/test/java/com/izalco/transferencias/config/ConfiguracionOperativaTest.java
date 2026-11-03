package com.izalco.transferencias.config;

import com.izalco.transferencias.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Configuración "de operación" que no debe cambiar por accidente: qué expone Actuator
 * y que la sesión de base de datos no quede abierta durante toda la solicitud HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ConfiguracionOperativaTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext contexto;

    @Test
    void healthRespondeUpSinDetallesInternos() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void exponeLasSondasParaKubernetes(String sonda) throws Exception {
        mvc.perform(get(sonda))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /** build-info lo genera el plugin de Spring Boot en el build de Maven: la versión exacta desplegada. */
    @Test
    void infoMuestraLaVersionDelArtefacto() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.artifact").value("transferencia-api"))
                .andExpect(jsonPath("$.build.version").isNotEmpty())
                .andExpect(jsonPath("$.build.time").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.startsWith("1980"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/heapdump"})
    void noExponeEndpointsQueRevelanConfiguracionInterna(String endpoint) throws Exception {
        mvc.perform(get(endpoint)).andExpect(status().isNotFound());
    }

    /**
     * Con open-in-view activo, Spring abre la sesión de JPA al inicio de cada solicitud y la cierra al final:
     * una conexión del pool queda tomada también mientras se espera al core. Con el core lento del incidente,
     * eso agotaría el pool. Si alguien lo reactiva, aparecería este interceptor y la prueba fallaría.
     */
    @Test
    void openInViewEstaDesactivado() {
        assertThat(contexto.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
        assertThat(contexto.getEnvironment().getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    }
}
