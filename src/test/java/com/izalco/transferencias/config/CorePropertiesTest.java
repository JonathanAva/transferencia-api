package com.izalco.transferencias.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el "fail fast": con configuración incompleta o incoherente, la aplicación no arranca.
 * {@link ApplicationContextRunner} levanta un contexto mínimo de Spring solo con estas propiedades.
 */
class CorePropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CoreProperties.class)
    static class SoloPropiedades {
    }

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(SoloPropiedades.class)
            .withPropertyValues(
                    "core.url=https://core-dev.izalco-digital.example/transfer",
                    "core.usuario=usuario-de-prueba",
                    "core.password=clave-falsa-solo-pruebas",
                    "core.timeout-conexion=2s",
                    "core.timeout-lectura=15s");

    @Test
    void arrancaConConfiguracionCompleta() {
        contexto.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            CoreProperties propiedades = ctx.getBean(CoreProperties.class);
            assertThat(propiedades.timeoutConexion()).isEqualTo(Duration.ofSeconds(2));
            assertThat(propiedades.timeoutLectura()).isEqualTo(Duration.ofSeconds(15));
        });
    }

    @Test
    void noArrancaSiFaltaLaPassword() {
        contexto.withPropertyValues("core.password=")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("password"));
    }

    @Test
    void noArrancaSiLaUrlNoEsHttp() {
        contexto.withPropertyValues("core.url=core-qa.local/transfer")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("url"));
    }

    @Test
    void noArrancaSiElTimeoutDeConexionNoEsMenorQueElDeLectura() {
        contexto.withPropertyValues("core.timeout-conexion=15s", "core.timeout-lectura=15s")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("timeout-conexion debe ser menor"));
    }

    @Test
    void noArrancaConTimeoutCero() {
        contexto.withPropertyValues("core.timeout-conexion=0s")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void toStringNoMuestraLaPassword() {
        CoreProperties propiedades = new CoreProperties("https://core.example/t", "usuario-de-prueba",
                "clave-falsa-solo-pruebas", Duration.ofSeconds(2), Duration.ofSeconds(15));

        assertThat(propiedades.toString())
                .doesNotContain("clave-falsa-solo-pruebas")
                .contains("password=****");
    }
}
