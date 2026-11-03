package com.izalco.transferencias.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

/**
 * Crea el cliente HTTP hacia el core con los dos timeouts configurados por separado.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoreProperties.class)
public class ConfiguracionClienteCore {

    @Bean
    RestClient restClientCore(CoreProperties propiedades) {
        return crearRestClient(propiedades);
    }

    /**
     * Pública y estática para que las pruebas construyan exactamente el mismo cliente sin levantar Spring.
     */
    public static RestClient crearRestClient(CoreProperties propiedades) {
        HttpClient httpClient = HttpClient.newBuilder()
                // Timeout de CONEXIÓN: si vence, se lanza HttpConnectTimeoutException.
                .connectTimeout(propiedades.timeoutConexion())
                // El core es un sistema legado: HTTP/1.1 explícito, sin intentar negociar HTTP/2.
                .version(HttpClient.Version.HTTP_1_1)
                // Un POST de dinero nunca debe seguir una redirección hacia otro destino.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(httpClient);
        // Timeout de LECTURA: si vence, Spring lanza HttpTimeoutException (sin "Connect").
        fabrica.setReadTimeout(propiedades.timeoutLectura());

        return RestClient.builder()
                .baseUrl(propiedades.url())
                .requestFactory(fabrica)
                .defaultHeaders(headers -> headers.setBasicAuth(
                        propiedades.usuario(), propiedades.password(), StandardCharsets.UTF_8))
                .build();
    }
}
