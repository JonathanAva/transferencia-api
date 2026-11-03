package com.izalco.transferencias.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotenciaProperties.class)
public class ConfiguracionIdempotencia {

    /** Reloj en UTC inyectable: todas las fechas de la API salen de aquí. */
    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }
}
