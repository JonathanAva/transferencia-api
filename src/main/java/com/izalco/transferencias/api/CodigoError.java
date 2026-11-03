package com.izalco.transferencias.api;

import org.springframework.http.HttpStatus;

import java.util.Locale;

/**
 * Códigos de negocio del contrato ({@code CodigoError}), con su status HTTP y título estable.
 * La app debe decidir su comportamiento con el código, no con el texto del mensaje.
 */
public enum CodigoError {

    SOLICITUD_INVALIDA(HttpStatus.BAD_REQUEST, "Solicitud inválida"),
    IDEMPOTENCY_KEY_REQUERIDA(HttpStatus.BAD_REQUEST, "Llave de idempotencia requerida"),
    IDEMPOTENCY_KEY_REUTILIZADA(HttpStatus.UNPROCESSABLE_CONTENT, "Llave de idempotencia reutilizada"),
    SOLICITUD_EN_PROCESO(HttpStatus.CONFLICT, "Solicitud en proceso"),
    TRANSFERENCIA_NO_ENCONTRADA(HttpStatus.NOT_FOUND, "Transferencia no encontrada"),
    SALDO_INSUFICIENTE(HttpStatus.UNPROCESSABLE_CONTENT, "Saldo insuficiente"),
    CUENTA_BLOQUEADA(HttpStatus.UNPROCESSABLE_CONTENT, "Cuenta bloqueada"),
    CORE_NO_DISPONIBLE(HttpStatus.SERVICE_UNAVAILABLE, "Core no disponible"),
    CORE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Tiempo de espera del core agotado"),
    RESPUESTA_CORE_INVALIDA(HttpStatus.BAD_GATEWAY, "Respuesta inválida del core"),
    ERROR_INTERNO(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno");

    private final HttpStatus status;
    private final String titulo;

    CodigoError(HttpStatus status, String titulo) {
        this.status = status;
        this.titulo = titulo;
    }

    public HttpStatus status() {
        return status;
    }

    public String titulo() {
        return titulo;
    }

    /** Parte final de la URI {@code type}: SALDO_INSUFICIENTE → saldo-insuficiente. */
    public String slug() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
