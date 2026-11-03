package com.izalco.transferencias.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Impide que la aplicación arranque con una configuración de ambiente peligrosa:
 * <ul>
 *   <li>Más de un perfil de ambiente a la vez (p. ej. {@code qa,prod}): no se sabe cuál "gana".</li>
 *   <li>Perfil {@code prod} con {@code core.url} o {@code spring.datasource.url} apuntando a algo de
 *       dev/qa. Es exactamente la Evidencia 2 del incidente: prod usaba {@code https://core-qa.izalco.local}.</li>
 * </ul>
 * Es un {@link BeanFactoryPostProcessor}: Spring lo ejecuta <b>antes de crear cualquier bean</b>. Así falla
 * antes de que Flyway se conecte a la base o de que se envíe una sola solicitud al core equivocado.
 */
public class GuardiaAmbiente implements BeanFactoryPostProcessor {

    static final Set<String> AMBIENTES = Set.of("dev", "qa", "prod");

    /** Palabras que delatan un recurso que no es de producción. */
    static final Set<String> MARCAS_NO_PROD = Set.of("dev", "desa", "desarrollo", "qa", "test", "pruebas", "uat", "staging");

    static final List<String> URLS_VIGILADAS = List.of("core.url", "spring.datasource.url");

    private final Environment entorno;

    public GuardiaAmbiente(Environment entorno) {
        this.entorno = entorno;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        verificar(entorno);
    }

    static void verificar(Environment entorno) {
        List<String> ambientes = Arrays.stream(entorno.getActiveProfiles()).filter(AMBIENTES::contains).toList();
        if (ambientes.size() > 1) {
            throw new IllegalStateException("Hay más de un perfil de ambiente activo " + ambientes
                    + ". Active exactamente uno de " + AMBIENTES + ".");
        }
        if (!ambientes.contains("prod")) {
            return;
        }
        for (String propiedad : URLS_VIGILADAS) {
            String url = entorno.getProperty(propiedad);
            marcaNoProductiva(url).ifPresent(marca -> {
                // No se imprime la URL completa: una URL JDBC puede llevar credenciales como parámetro.
                throw new IllegalStateException("El perfil prod no puede usar un recurso de otro ambiente: "
                        + propiedad + " contiene '" + marca + "'. Revise la configuración del despliegue.");
            });
        }
    }

    /**
     * Divide la URL en palabras (separadas por cualquier carácter que no sea letra o número) y busca una marca
     * de ambiente no productivo. Se comparan palabras completas, no subcadenas: {@code core-qa.izalco.local}
     * se detecta ("qa"), pero {@code devoluciones.izalco.com} no ("devoluciones" no es "dev").
     */
    static Optional<String> marcaNoProductiva(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(url.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(MARCAS_NO_PROD::contains)
                .findFirst();
    }
}
