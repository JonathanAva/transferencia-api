package com.izalco.transferencias.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuración de las llaves de idempotencia (prefijo {@code idempotencia}).
 *
 * @param expiracion      tiempo durante el cual se recuerda una llave; pasado ese tiempo puede reutilizarse
 * @param margenEnProceso margen que se suma al timeout de lectura del core para decidir que una llave
 *                        {@code EN_PROCESO} quedó "atrapada" (por ejemplo, el pod murió a mitad de la llamada)
 */
@Validated
@ConfigurationProperties(prefix = "idempotencia")
public record IdempotenciaProperties(
        @NotNull @DurationMin(minutes = 1) Duration expiracion,
        @NotNull @DurationMin(millis = 1) Duration margenEnProceso
) {
}
