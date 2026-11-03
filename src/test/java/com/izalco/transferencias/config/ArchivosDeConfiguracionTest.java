package com.izalco.transferencias.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Revisa los archivos de configuración que viajan dentro del artefacto. En el incidente,
 * config-prod.yaml tenía la contraseña del core en texto plano y la URL del core de QA escrita a mano.
 */
class ArchivosDeConfiguracionTest {

    /** Claves que contienen secretos. Su valor solo puede ser una referencia a variable de entorno. */
    private static final Pattern CLAVE_SECRETA = Pattern.compile("(?i).*(password|secret|clave|token|credencial|usuario|username).*");

    /** "${VARIABLE}" o "${VARIABLE:}": sin valor por defecto, para que falte y la app no arranque. */
    private static final Pattern REFERENCIA_SIN_DEFAULT = Pattern.compile("^\\$\\{[A-Z0-9_]+:?}$");

    private static Map<String, String> propiedades(String archivo) throws IOException {
        Map<String, String> resultado = new LinkedHashMap<>();
        for (PropertySource<?> fuente : new YamlPropertySourceLoader().load(archivo, new ClassPathResource(archivo))) {
            EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) fuente;
            for (String nombre : enumerable.getPropertyNames()) {
                resultado.put(nombre, String.valueOf(enumerable.getProperty(nombre)));
            }
        }
        return resultado;
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yaml", "application-dev.yaml", "application-qa.yaml", "application-prod.yaml"})
    void ningunSecretoEstaEscritoEnElArchivo(String archivo) throws IOException {
        Map<String, String> propiedades = propiedades(archivo);
        assertThat(propiedades).as("el archivo %s existe y tiene contenido", archivo).isNotEmpty();

        propiedades.forEach((clave, valor) -> {
            if (CLAVE_SECRETA.matcher(clave).matches()) {
                assertThat(valor).as("%s en %s debe venir de una variable de entorno sin valor por defecto", clave, archivo)
                        .matches(REFERENCIA_SIN_DEFAULT);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yaml", "application-dev.yaml", "application-qa.yaml", "application-prod.yaml"})
    void ningunaUrlEstaEscritaEnElArchivo(String archivo) throws IOException {
        propiedades(archivo).forEach((clave, valor) ->
                assertThat(valor).as("%s en %s: las URLs llegan por variable de entorno", clave, archivo)
                        .doesNotContainPattern("(?i)(https?|jdbc):"));
    }

    /** Logs en JSON (ECS) en los ambientes compartidos: el correlationId queda como campo buscable. */
    @ParameterizedTest
    @ValueSource(strings = {"qa", "prod"})
    void qaYProdUsanLogsEstructurados(String ambiente) throws IOException {
        assertThat(propiedades("application-" + ambiente + ".yaml"))
                .containsEntry("logging.structured.format.console", "ecs");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "qa", "prod"})
    void cadaAmbienteSeIdentificaEnInfo(String ambiente) throws IOException {
        assertThat(propiedades("application-" + ambiente + ".yaml")).containsEntry("info.ambiente", ambiente);
    }
}
