package com.izalco.transferencias.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Solicitud de transferencia enviada por la app móvil (cuerpo JSON del POST).
 * Las reglas replican el esquema {@code SolicitudTransferencia} del contrato OpenAPI.
 */
public record SolicitudTransferencia(

        @NotBlank
        @Pattern(regexp = "^[0-9]{6,20}$", message = "debe contener solo dígitos (6 a 20)")
        String cuentaOrigen,

        @NotBlank
        @Pattern(regexp = "^[0-9]{6,20}$", message = "debe contener solo dígitos (6 a 20)")
        String cuentaDestino,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}-[0-9]{4}$", message = "debe tener el formato AAA-9999")
        String bancoDestino,

        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "debe ser mayor que 0")
        @MontoValido
        BigDecimal monto,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "debe ser un código ISO 4217 de 3 letras mayúsculas")
        String moneda,

        @NotBlank
        @Size(max = 140)
        String concepto,

        @NotNull
        OffsetDateTime fechaSolicitud
) {

    /**
     * Regla entre campos: no se puede transferir a la misma cuenta.
     * Si alguna cuenta falta, la regla no aplica (ya lo reporta {@code @NotBlank}).
     */
    @JsonIgnore
    @AssertTrue(message = "la cuenta destino debe ser distinta de la cuenta origen")
    public boolean isCuentasDistintas() {
        return cuentaOrigen == null || !cuentaOrigen.equals(cuentaDestino);
    }
}
