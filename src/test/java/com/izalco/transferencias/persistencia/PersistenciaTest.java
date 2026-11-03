package com.izalco.transferencias.persistencia;

import com.izalco.transferencias.TestcontainersConfiguration;
import com.izalco.transferencias.dominio.EstadoTransferencia;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas contra PostgreSQL real (Testcontainers): migraciones Flyway, mapeo JPA y operaciones atómicas.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PersistenciaTest {

    private static final String HASH = "a".repeat(64);

    @Autowired
    private TransferenciaRepositorio transferencias;

    @Autowired
    private IdempotenciaRepositorio llaves;

    @Autowired
    private JdbcTemplate jdbc;

    private final Instant ahora = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private UUID guardarTransferencia() {
        UUID id = UUID.randomUUID();
        transferencias.save(new TransferenciaEntidad(id, "100200300", "900800700", "BIZ-0042",
                new BigDecimal("150.75"), "USD", "Pago de alquiler",
                OffsetDateTime.parse("2026-10-01T14:32:10-06:00"),
                EstadoTransferencia.PENDIENTE_CONFIRMACION, "En proceso", ahora));
        return id;
    }

    @Test
    void guardaYLeeLaTransferenciaConservandoElOffsetOriginal() {
        UUID id = guardarTransferencia();

        TransferenciaEntidad leida = transferencias.findById(id).orElseThrow();

        assertThat(leida.getFechaSolicitud()).isEqualTo(OffsetDateTime.parse("2026-10-01T14:32:10-06:00"));
        assertThat(leida.getFechaSolicitud().getOffset().toString()).isEqualTo("-06:00");
        assertThat(leida.getMonto()).isEqualByComparingTo("150.75");
        assertThat(leida.getEstado()).isEqualTo(EstadoTransferencia.PENDIENTE_CONFIRMACION);
        // En la base se guarda el instante UTC
        assertThat(jdbc.queryForObject(
                "SELECT to_char(fecha_solicitud AT TIME ZONE 'UTC', 'HH24:MI') FROM transferencia WHERE id = ?",
                String.class, id)).isEqualTo("20:32");
    }

    @Test
    void laBaseRechazaMontosNoPositivos() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO transferencia (id, cuenta_origen, cuenta_destino, banco_destino, monto, moneda, concepto,
                  fecha_solicitud, offset_solicitud_segundos, estado, mensaje, fecha_registro, fecha_actualizacion)
                VALUES (gen_random_uuid(), '1', '2', 'BIZ-0042', 0, 'USD', 'x', now(), 0, 'APROBADA', 'x', now(), now())
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laLlaveSoloSePuedeReservarUnaVez() {
        UUID llave = UUID.randomUUID();
        UUID transferenciaId = guardarTransferencia();
        Instant expira = ahora.plus(Duration.ofHours(24));

        assertThat(llaves.reservar(llave, HASH, transferenciaId, ahora, expira)).isEqualTo(1);
        assertThat(llaves.reservar(llave, HASH, transferenciaId, ahora, expira)).isZero();

        IdempotenciaEntidad guardada = llaves.findById(llave).orElseThrow();
        assertThat(guardada.getEstado()).isEqualTo(EstadoLlave.EN_PROCESO);
        assertThat(guardada.getTransferenciaId()).isEqualTo(transferenciaId);
    }

    @Test
    void completarSoloFuncionaUnaVezYGuardaLaRespuesta() {
        UUID llave = UUID.randomUUID();
        llaves.reservar(llave, HASH, guardarTransferencia(), ahora, ahora.plus(Duration.ofHours(24)));

        assertThat(llaves.completar(llave, 201, "{\"estado\":\"APROBADA\"}")).isEqualTo(1);
        assertThat(llaves.completar(llave, 504, "{}")).isZero();

        IdempotenciaEntidad guardada = llaves.findById(llave).orElseThrow();
        assertThat(guardada.getEstado()).isEqualTo(EstadoLlave.COMPLETADA);
        assertThat(guardada.getHttpStatus()).isEqualTo(201);
        assertThat(guardada.getRespuesta()).isEqualTo("{\"estado\":\"APROBADA\"}");
    }

    @Test
    void liberarSoloBorraLlavesEnProceso() {
        UUID enProceso = UUID.randomUUID();
        UUID completada = UUID.randomUUID();
        Instant expira = ahora.plus(Duration.ofHours(24));
        llaves.reservar(enProceso, HASH, guardarTransferencia(), ahora, expira);
        llaves.reservar(completada, HASH, guardarTransferencia(), ahora, expira);
        llaves.completar(completada, 201, "{}");

        assertThat(llaves.liberar(enProceso)).isEqualTo(1);
        assertThat(llaves.liberar(completada)).isZero();
        assertThat(llaves.existsById(enProceso)).isFalse();
        assertThat(llaves.existsById(completada)).isTrue();
    }

    @Test
    void borrarSiVencioSoloBorraLlavesVencidas() {
        UUID vencida = UUID.randomUUID();
        UUID vigente = UUID.randomUUID();
        llaves.reservar(vencida, HASH, guardarTransferencia(), ahora.minus(Duration.ofHours(25)), ahora.minus(Duration.ofHours(1)));
        llaves.reservar(vigente, HASH, guardarTransferencia(), ahora, ahora.plus(Duration.ofHours(24)));

        assertThat(llaves.borrarSiVencio(vencida, ahora)).isEqualTo(1);
        assertThat(llaves.borrarSiVencio(vigente, ahora)).isZero();
    }

    @Test
    void laBaseExigeRespuestaParaMarcarUnaLlaveComoCompletada() {
        UUID llave = UUID.randomUUID();
        llaves.reservar(llave, HASH, guardarTransferencia(), ahora, ahora.plus(Duration.ofHours(24)));

        assertThatThrownBy(() -> jdbc.update("UPDATE idempotencia SET estado = 'COMPLETADA' WHERE llave = ?", llave))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
