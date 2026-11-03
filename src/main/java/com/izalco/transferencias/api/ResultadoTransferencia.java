package com.izalco.transferencias.api;

import com.izalco.transferencias.dominio.EstadoTransferencia;

import java.time.Instant;
import java.util.UUID;

/**
 * Respuesta de {@code POST /transferencias} cuando el core aprueba la transferencia (201).
 */
public record ResultadoTransferencia(
        UUID id,
        EstadoTransferencia estado,
        String referenciaCore,
        String mensaje,
        Instant fechaRegistro
) {
}
