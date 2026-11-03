package com.izalco.transferencias.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardiaAmbienteTest {

    private static final String CORE_PROD = "https://core.izalco.local/transfer";
    private static final String DB_PROD = "jdbc:postgresql://db-transferencias.izalco.local:5432/transferencias";

    private static MockEnvironment entorno(String perfiles, String coreUrl, String dbUrl) {
        MockEnvironment entorno = new MockEnvironment();
        if (!perfiles.isEmpty()) {
            entorno.setActiveProfiles(perfiles.split(","));
        }
        if (coreUrl != null) {
            entorno.setProperty("core.url", coreUrl);
        }
        if (dbUrl != null) {
            entorno.setProperty("spring.datasource.url", dbUrl);
        }
        return entorno;
    }

    /** Evidencia 2 del incidente, literal. */
    @Test
    void prodConElCoreDeQaNoArranca() {
        assertThatThrownBy(() -> GuardiaAmbiente.verificar(entorno("prod", "https://core-qa.izalco.local/transfer", DB_PROD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core.url")
                .hasMessageContaining("'qa'");
    }

    @Test
    void prodConLaBaseDeDatosDeOtroAmbienteNoArrancaYNoMuestraLaUrl() {
        String dbQa = "jdbc:postgresql://db.izalco.local:5432/transferencias_dev?password=no-debe-verse";
        assertThatThrownBy(() -> GuardiaAmbiente.verificar(entorno("prod", CORE_PROD, dbQa)))
                .hasMessageContaining("spring.datasource.url")
                .hasMessageContaining("'dev'")
                .message().doesNotContain("no-debe-verse");
    }

    @Test
    void prodConUrlsDeProduccionArranca() {
        assertThatCode(() -> GuardiaAmbiente.verificar(entorno("prod", CORE_PROD, DB_PROD))).doesNotThrowAnyException();
    }

    /** QA puede (y debe) usar recursos de QA: la regla solo aplica a prod. */
    @Test
    void qaConRecursosDeQaArranca() {
        assertThatCode(() -> GuardiaAmbiente.verificar(
                entorno("qa", "https://core-qa.izalco.local/transfer", "jdbc:postgresql://db-qa:5432/t")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"qa,prod", "dev,qa", "dev,prod"})
    void dosPerfilesDeAmbienteALaVezNoArrancan(String perfiles) {
        assertThatThrownBy(() -> GuardiaAmbiente.verificar(entorno(perfiles, CORE_PROD, DB_PROD)))
                .hasMessageContaining("más de un perfil de ambiente");
    }

    /** Perfiles que no son de ambiente (p. ej. "test" de las pruebas) no cuentan. */
    @Test
    void perfilesQueNoSonDeAmbienteNoCuentan() {
        assertThatCode(() -> GuardiaAmbiente.verificar(entorno("test,prod", CORE_PROD, DB_PROD))).doesNotThrowAnyException();
    }

    @Test
    void sinUrlConfiguradaLaGuardiaNoOpina() {
        // Si falta la URL, ya fallará la validación de CoreProperties o del DataSource con su propio mensaje.
        assertThatCode(() -> GuardiaAmbiente.verificar(entorno("prod", null, ""))).doesNotThrowAnyException();
    }

    @Test
    void comparaPalabrasCompletasNoSubcadenas() {
        assertThat(GuardiaAmbiente.marcaNoProductiva("https://core-qa.izalco.local/transfer")).contains("qa");
        assertThat(GuardiaAmbiente.marcaNoProductiva("https://CORE.UAT.izalco.local")).contains("uat");
        assertThat(GuardiaAmbiente.marcaNoProductiva("https://devoluciones.izalco.com/core")).isEmpty();
        assertThat(GuardiaAmbiente.marcaNoProductiva("https://core.izalco.local/qatar")).isEmpty();
    }

    /** De punta a punta: el contexto de Spring realmente no levanta. */
    @Test
    void elContextoDeSpringNoLevantaEnProdConElCoreDeQa() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConfiguracionAmbiente.class)
                .withPropertyValues("spring.profiles.active=prod", "core.url=https://core-qa.izalco.local/transfer")
                .run(contexto -> assertThat(contexto).hasFailed()
                        .getFailure().isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("core.url contiene 'qa'"));
    }

    @Test
    void elContextoDeSpringLevantaEnProdConElCoreDeProd() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConfiguracionAmbiente.class)
                .withPropertyValues("spring.profiles.active=prod", "core.url=" + CORE_PROD)
                .run(contexto -> assertThat(contexto).hasNotFailed());
    }
}
