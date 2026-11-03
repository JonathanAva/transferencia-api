package com.izalco.transferencias.persistencia;

import com.izalco.transferencias.dominio.EstadoTransferencia;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Fila de la tabla {@code transferencia}.
 * <p>
 * Implementa {@link Persistable} porque el {@code id} lo asigna la aplicación (UUID): así Spring Data sabe
 * que es nueva y hace un INSERT directo, en vez de un SELECT previo para decidir entre INSERT y UPDATE.
 */
@Entity
@Table(name = "transferencia")
public class TransferenciaEntidad implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "cuenta_origen", nullable = false, length = 20)
    private String cuentaOrigen;

    @Column(name = "cuenta_destino", nullable = false, length = 20)
    private String cuentaDestino;

    @Column(name = "banco_destino", nullable = false, length = 8)
    private String bancoDestino;

    @Column(name = "monto", nullable = false, precision = 15, scale = 2)
    private BigDecimal monto;

    @Column(name = "moneda", nullable = false, length = 3)
    private String moneda;

    @Column(name = "concepto", nullable = false, length = 140)
    private String concepto;

    @Column(name = "fecha_solicitud", nullable = false)
    private Instant fechaSolicitud;

    @Column(name = "offset_solicitud_segundos", nullable = false)
    private int offsetSolicitudSegundos;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 30)
    private EstadoTransferencia estado;

    @Column(name = "referencia_core", length = 64)
    private String referenciaCore;

    @Column(name = "mensaje", nullable = false)
    private String mensaje;

    @Column(name = "fecha_registro", nullable = false)
    private Instant fechaRegistro;

    @Column(name = "fecha_actualizacion", nullable = false)
    private Instant fechaActualizacion;

    @Transient
    private boolean nueva = true;

    /** Requerido por JPA. */
    protected TransferenciaEntidad() {
    }

    public TransferenciaEntidad(UUID id, String cuentaOrigen, String cuentaDestino, String bancoDestino,
                                BigDecimal monto, String moneda, String concepto, OffsetDateTime fechaSolicitud,
                                EstadoTransferencia estado, String mensaje, Instant ahora) {
        this.id = id;
        this.cuentaOrigen = cuentaOrigen;
        this.cuentaDestino = cuentaDestino;
        this.bancoDestino = bancoDestino;
        this.monto = monto;
        this.moneda = moneda;
        this.concepto = concepto;
        this.fechaSolicitud = fechaSolicitud.toInstant();
        this.offsetSolicitudSegundos = fechaSolicitud.getOffset().getTotalSeconds();
        this.estado = estado;
        this.mensaje = mensaje;
        this.fechaRegistro = ahora;
        this.fechaActualizacion = ahora;
    }

    /** Registra el resultado del core (o la confirmación de que es incierto). */
    public void registrarResultado(EstadoTransferencia nuevoEstado, String referencia, String nuevoMensaje,
                                   Instant ahora) {
        this.estado = nuevoEstado;
        this.referenciaCore = referencia;
        this.mensaje = nuevoMensaje;
        this.fechaActualizacion = ahora;
    }

    /** Reconstruye la fecha con el offset original enviado por la app. */
    public OffsetDateTime getFechaSolicitud() {
        return fechaSolicitud.atOffset(ZoneOffset.ofTotalSeconds(offsetSolicitudSegundos));
    }

    @PostLoad
    @PostPersist
    void marcarComoExistente() {
        this.nueva = false;
    }

    @Override
    public boolean isNew() {
        return nueva;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getCuentaOrigen() {
        return cuentaOrigen;
    }

    public String getCuentaDestino() {
        return cuentaDestino;
    }

    public String getBancoDestino() {
        return bancoDestino;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public String getMoneda() {
        return moneda;
    }

    public String getConcepto() {
        return concepto;
    }

    public EstadoTransferencia getEstado() {
        return estado;
    }

    public String getReferenciaCore() {
        return referenciaCore;
    }

    public String getMensaje() {
        return mensaje;
    }

    public Instant getFechaRegistro() {
        return fechaRegistro;
    }
}
