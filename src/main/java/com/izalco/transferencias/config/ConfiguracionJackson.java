package com.izalco.transferencias.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * Ajustes del JSON de la API (Jackson 3).
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguracionJackson {

    @Bean
    JsonMapperBuilderCustomizer reglasJsonDeLaApi() {
        return builder -> builder
                // El contrato declara additionalProperties: false. Un campo desconocido (p. ej. "montto")
                // es un error del cliente y se rechaza con 400, en vez de ignorarse en silencio.
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Por defecto Jackson convierte las fechas a UTC al leerlas y se pierde el offset original
                // (-06:00). Se conserva para guardarlo y devolverlo tal como lo envió la app.
                .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    }
}
