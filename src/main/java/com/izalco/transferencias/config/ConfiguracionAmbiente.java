package com.izalco.transferencias.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class ConfiguracionAmbiente {

    /**
     * {@code static}: los BeanFactoryPostProcessor se crean antes que la propia clase de configuración,
     * así que Spring exige que el método que los declara sea estático.
     */
    @Bean
    static GuardiaAmbiente guardiaAmbiente(Environment entorno) {
        return new GuardiaAmbiente(entorno);
    }
}
