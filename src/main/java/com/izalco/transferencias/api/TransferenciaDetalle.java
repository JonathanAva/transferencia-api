package com.izalco.transferencias.api;

import com.izalco.transferencias.dominio.EstadoTransferencia;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Respuesta de {@code GET /transferencias/{id}}: el resultado más los datos de la solicitud.
 * Corresponde al esquema {@code Transferencia} del contrato.
 */
public record TransferenciaDetalle(
        UUID id,
        EstadoTransferencia estado,
        String referenciaCore,
        String mensaje,
        Instant fechaRegistro,
        String cuentaOrigen,
        String cuentaDestino,
        String bancoDestino,
        BigDecimal monto,
        String moneda,
        String concepto,
        OffsetDateTime fechaSolicitud
) {
}
