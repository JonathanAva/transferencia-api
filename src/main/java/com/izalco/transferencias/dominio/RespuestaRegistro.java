package com.izalco.transferencias.dominio;

import java.util.UUID;

/**
 * Respuesta HTTP de un registro, tal como se guarda para la idempotencia.
 *
 * @param status          status HTTP (201, 422, 502 o 504)
 * @param cuerpo          cuerpo JSON exacto (se devuelve idéntico en cada reintento)
 * @param transferenciaId transferencia asociada
 * @param repetida        {@code true} si es una respuesta guardada devuelta otra vez (header Idempotent-Replayed)
 */
public record RespuestaRegistro(int status, String cuerpo, UUID transferenciaId, boolean repetida) {
}
