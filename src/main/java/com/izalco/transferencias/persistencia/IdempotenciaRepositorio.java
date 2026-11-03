package com.izalco.transferencias.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Operaciones atómicas sobre las llaves de idempotencia. Cada una devuelve cuántas filas cambió:
 * 1 si ganó, 0 si otra solicitud se adelantó o la llave no estaba en el estado esperado.
 */
public interface IdempotenciaRepositorio extends JpaRepository<IdempotenciaEntidad, UUID> {

    /**
     * Intenta reservar la llave. {@code ON CONFLICT DO NOTHING} es atómico en PostgreSQL: si dos réplicas
     * insertan la misma llave a la vez, una inserta (1) y la otra espera y no hace nada (0).
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO idempotencia (llave, hash_solicitud, transferencia_id, estado, creado_en, expira_en)
            VALUES (:llave, :hash, :transferenciaId, 'EN_PROCESO', :creadoEn, :expiraEn)
            ON CONFLICT (llave) DO NOTHING
            """, nativeQuery = true)
    int reservar(@Param("llave") UUID llave, @Param("hash") String hashSolicitud,
                 @Param("transferenciaId") UUID transferenciaId,
                 @Param("creadoEn") Instant creadoEn, @Param("expiraEn") Instant expiraEn);

    /** Guarda la respuesta definitiva, solo si la llave sigue EN_PROCESO (nadie la cerró antes). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE IdempotenciaEntidad i
               SET i.estado = :completada, i.httpStatus = :httpStatus, i.respuesta = :respuesta
             WHERE i.llave = :llave AND i.estado = :enProceso
            """)
    int completar(@Param("llave") UUID llave, @Param("httpStatus") int httpStatus,
                  @Param("respuesta") String respuesta,
                  @Param("completada") EstadoLlave completada, @Param("enProceso") EstadoLlave enProceso);

    default int completar(UUID llave, int httpStatus, String respuesta) {
        return completar(llave, httpStatus, respuesta, EstadoLlave.COMPLETADA, EstadoLlave.EN_PROCESO);
    }

    /** Libera la llave (el core NO procesó la solicitud), solo si sigue EN_PROCESO. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM IdempotenciaEntidad i WHERE i.llave = :llave AND i.estado = :enProceso")
    int liberar(@Param("llave") UUID llave, @Param("enProceso") EstadoLlave enProceso);

    default int liberar(UUID llave) {
        return liberar(llave, EstadoLlave.EN_PROCESO);
    }

    /** Borra la llave solo si ya venció (pasadas las 24 h se puede reutilizar). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM IdempotenciaEntidad i WHERE i.llave = :llave AND i.expiraEn < :ahora")
    int borrarSiVencio(@Param("llave") UUID llave, @Param("ahora") Instant ahora);
}
