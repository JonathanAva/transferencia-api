package com.izalco.transferencias.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Fila de la tabla {@code idempotencia}. Solo se lee con JPA: las escrituras se hacen con consultas
 * atómicas en {@link IdempotenciaRepositorio} para que funcionen bien con solicitudes concurrentes.
 */
@Entity
@Table(name = "idempotencia")
public class IdempotenciaEntidad {

    @Id
    private UUID llave;

    @Column(name = "hash_solicitud", nullable = false, length = 64)
    private String hashSolicitud;

    @Column(name = "transferencia_id", nullable = false)
    private UUID transferenciaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private EstadoLlave estado;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "respuesta", columnDefinition = "text")
    private String respuesta;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "expira_en", nullable = false)
    private Instant expiraEn;

    /** Requerido por JPA. */
    protected IdempotenciaEntidad() {
    }

    public UUID getLlave() {
        return llave;
    }

    public String getHashSolicitud() {
        return hashSolicitud;
    }

    public UUID getTransferenciaId() {
        return transferenciaId;
    }

    public EstadoLlave getEstado() {
        return estado;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getRespuesta() {
        return respuesta;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getExpiraEn() {
        return expiraEn;
    }
}
