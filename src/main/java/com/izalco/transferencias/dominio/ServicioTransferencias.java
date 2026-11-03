package com.izalco.transferencias.dominio;

import com.izalco.transferencias.api.CodigoError;
import com.izalco.transferencias.api.FabricaProblemas;
import com.izalco.transferencias.api.ResultadoTransferencia;
import com.izalco.transferencias.api.SolicitudTransferencia;
import com.izalco.transferencias.api.TransferenciaDetalle;
import com.izalco.transferencias.config.CoreProperties;
import com.izalco.transferencias.config.IdempotenciaProperties;
import com.izalco.transferencias.core.ClienteCore;
import com.izalco.transferencias.core.CoreTimeoutException;
import com.izalco.transferencias.core.FallaCoreException;
import com.izalco.transferencias.core.ResultadoCore;
import com.izalco.transferencias.idempotencia.HashSolicitud;
import com.izalco.transferencias.idempotencia.LlaveReutilizadaException;
import com.izalco.transferencias.idempotencia.SolicitudEnProcesoException;
import com.izalco.transferencias.persistencia.EstadoLlave;
import com.izalco.transferencias.persistencia.IdempotenciaEntidad;
import com.izalco.transferencias.persistencia.IdempotenciaRepositorio;
import com.izalco.transferencias.persistencia.TransferenciaEntidad;
import com.izalco.transferencias.persistencia.TransferenciaRepositorio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Casos de uso de transferencias: registro con idempotencia y consulta.
 * <p>
 * Regla de oro: <b>solo se vuelve a llamar al core si hay certeza de que no procesó la transferencia</b>.
 * Por eso la transferencia y la llave se guardan <i>antes</i> de llamar al core: si el proceso muere a
 * mitad, ya queda registrado que el resultado es incierto.
 */
@Service
public class ServicioTransferencias {

    private static final Logger log = LoggerFactory.getLogger(ServicioTransferencias.class);

    static final String MENSAJE_EN_PROCESO = "Transferencia enviada al core; resultado pendiente";
    static final String MENSAJE_APROBADA = "Transferencia aprobada";
    static final String DETALLE_SALDO = "La cuenta origen no tiene saldo suficiente para realizar la transferencia.";
    static final String DETALLE_BLOQUEADA = "La cuenta origen está bloqueada. Contacte a su agencia.";
    static final String DETALLE_TIMEOUT =
            "El sistema central no respondió a tiempo. La transferencia queda pendiente de confirmación.";
    static final String DETALLE_INVALIDA =
            "El core bancario devolvió una respuesta no reconocida. La transferencia queda pendiente de confirmación.";
    static final String DETALLE_ATRAPADA =
            "La solicitud original no terminó a tiempo. La transferencia queda pendiente de confirmación.";

    /** Una llave puede desaparecer entre dos pasos (liberada o vencida): se reintenta la reserva unas pocas veces. */
    private static final int MAX_INTENTOS_RESERVA = 3;

    private final TransferenciaRepositorio transferencias;
    private final IdempotenciaRepositorio llaves;
    private final ClienteCore clienteCore;
    private final TransactionTemplate tx;
    private final JsonMapper jsonMapper;
    private final Clock reloj;
    private final IdempotenciaProperties idempotencia;
    private final CoreProperties core;

    public ServicioTransferencias(TransferenciaRepositorio transferencias, IdempotenciaRepositorio llaves,
                                  ClienteCore clienteCore, PlatformTransactionManager transacciones,
                                  JsonMapper jsonMapper, Clock reloj,
                                  IdempotenciaProperties idempotencia, CoreProperties core) {
        this.transferencias = transferencias;
        this.llaves = llaves;
        this.clienteCore = clienteCore;
        this.tx = new TransactionTemplate(transacciones);
        this.jsonMapper = jsonMapper;
        this.reloj = reloj;
        this.idempotencia = idempotencia;
        this.core = core;
    }

    // =====================================================================================
    // Registro
    // =====================================================================================

    /**
     * Registra una transferencia aplicando idempotencia.
     *
     * @return respuesta a devolver (nueva o repetida)
     * @throws LlaveReutilizadaException                              misma llave con otro contenido (422)
     * @throws SolicitudEnProcesoException                            la primera solicitud sigue en curso (409)
     * @throws com.izalco.transferencias.core.CoreNoDisponibleException el core no la procesó; llave liberada (503)
     */
    public RespuestaRegistro registrar(UUID llave, SolicitudTransferencia solicitud, String ruta) {
        String hash = HashSolicitud.calcular(solicitud);

        for (int intento = 1; intento <= MAX_INTENTOS_RESERVA; intento++) {
            UUID id = UUID.randomUUID();
            if (reservar(llave, hash, id, solicitud)) {
                // Esta solicitud es dueña de la llave.
                return procesarConElCore(llave, id, solicitud, ruta);
            }
            Optional<RespuestaRegistro> existente = resolverLlaveExistente(llave, hash, ruta);
            if (existente.isPresent()) {
                return existente.get();
            }
            // La llave desapareció entre la reserva y la lectura: se intenta reservar otra vez.
        }
        throw new IllegalStateException("No se pudo reservar la llave " + llave);
    }

    /**
     * Transacción corta: guarda la transferencia como PENDIENTE_CONFIRMACION y reserva la llave EN_PROCESO.
     * Si la llave ya existía, se deshace todo (la transferencia recién creada no queda guardada).
     */
    private boolean reservar(UUID llave, String hash, UUID id, SolicitudTransferencia s) {
        Instant ahora = ahora();
        return Boolean.TRUE.equals(tx.execute(estado -> {
            transferencias.save(new TransferenciaEntidad(id, s.cuentaOrigen(), s.cuentaDestino(), s.bancoDestino(),
                    s.monto(), s.moneda(), s.concepto(), s.fechaSolicitud(),
                    EstadoTransferencia.PENDIENTE_CONFIRMACION, MENSAJE_EN_PROCESO, ahora));
            if (llaves.reservar(llave, hash, id, ahora, ahora.plus(idempotencia.expiracion())) == 1) {
                return true;
            }
            estado.setRollbackOnly();
            return false;
        }));
    }

    /** Decide qué hacer cuando la llave ya existe. {@code Optional.empty()} significa "reintentar la reserva". */
    private Optional<RespuestaRegistro> resolverLlaveExistente(UUID llave, String hash, String ruta) {
        Instant ahora = ahora();
        IdempotenciaEntidad existente = llaves.findById(llave).orElse(null);

        if (existente == null) {
            return Optional.empty();
        }
        if (existente.getExpiraEn().isBefore(ahora)) {
            llaves.borrarSiVencio(llave, ahora);
            return Optional.empty();
        }
        if (!existente.getHashSolicitud().equals(hash)) {
            throw new LlaveReutilizadaException();
        }
        if (existente.getEstado() == EstadoLlave.COMPLETADA) {
            return Optional.of(repetida(existente));
        }
        if (estaAtrapada(existente, ahora)) {
            return Optional.of(cerrarComoIncierta(existente, ruta));
        }
        throw new SolicitudEnProcesoException();
    }

    /**
     * Una llave EN_PROCESO por más tiempo que el timeout de lectura del core más un margen ya no puede estar
     * esperando al core: el proceso que la tenía murió (pod caído, despliegue, OOM...).
     */
    private boolean estaAtrapada(IdempotenciaEntidad llave, Instant ahora) {
        Duration umbral = core.timeoutLectura().plus(idempotencia.margenEnProceso());
        return llave.getCreadoEn().plus(umbral).isBefore(ahora);
    }

    /**
     * Cierra una llave atrapada como resultado incierto: la transferencia queda PENDIENTE_CONFIRMACION y la llave
     * COMPLETADA con un 504. <b>Nunca</b> se libera ni se vuelve a llamar al core: el proceso que murió pudo
     * haber enviado la transferencia.
     */
    private RespuestaRegistro cerrarComoIncierta(IdempotenciaEntidad llave, String ruta) {
        UUID id = llave.getTransferenciaId();
        String cuerpo = json(FabricaProblemas.conTransferencia(CodigoError.CORE_TIMEOUT, DETALLE_ATRAPADA, ruta, id));

        boolean cerrada = Boolean.TRUE.equals(tx.execute(estado -> {
            if (llaves.completar(llave.getLlave(), CodigoError.CORE_TIMEOUT.status().value(), cuerpo) == 0) {
                estado.setRollbackOnly();
                return false;
            }
            transferencias.findById(id).ifPresent(t -> t.registrarResultado(
                    EstadoTransferencia.PENDIENTE_CONFIRMACION, t.getReferenciaCore(), DETALLE_ATRAPADA, ahora()));
            return true;
        }));

        if (cerrada) {
            log.warn("Llave {} atrapada en EN_PROCESO desde {}: transferencia {} queda PENDIENTE_CONFIRMACION",
                    llave.getLlave(), llave.getCreadoEn(), id);
            return new RespuestaRegistro(CodigoError.CORE_TIMEOUT.status().value(), cuerpo, id, false);
        }
        // Otra solicitud la cerró justo antes: se devuelve lo que quedó guardado.
        return repetida(llaves.findById(llave.getLlave()).orElseThrow());
    }

    /** Llama al core (fuera de cualquier transacción) y guarda el resultado. */
    private RespuestaRegistro procesarConElCore(UUID llave, UUID id, SolicitudTransferencia solicitud, String ruta) {
        ResultadoCore resultado;
        try {
            resultado = clienteCore.enviar(solicitud);
        } catch (FallaCoreException falla) {
            if (!falla.puedeHaberseProcesado()) {
                liberar(llave, id);
                throw falla;
            }
            CodigoError codigo = falla instanceof CoreTimeoutException
                    ? CodigoError.CORE_TIMEOUT : CodigoError.RESPUESTA_CORE_INVALIDA;
            String detalle = codigo == CodigoError.CORE_TIMEOUT ? DETALLE_TIMEOUT : DETALLE_INVALIDA;
            return guardarResultado(llave, id, EstadoTransferencia.PENDIENTE_CONFIRMACION, null, detalle, codigo,
                    t -> FabricaProblemas.conTransferencia(codigo, detalle, ruta, id));
        }

        return switch (resultado) {
            case ResultadoCore.Aprobada a -> guardarResultado(llave, id, EstadoTransferencia.APROBADA,
                    a.referencia(), MENSAJE_APROBADA, null,
                    t -> new ResultadoTransferencia(id, EstadoTransferencia.APROBADA, a.referencia(),
                            MENSAJE_APROBADA, t.getFechaRegistro()));
            case ResultadoCore.Rechazada r -> {
                CodigoError codigo = switch (r.motivo()) {
                    case SALDO_INSUFICIENTE -> CodigoError.SALDO_INSUFICIENTE;
                    case CUENTA_BLOQUEADA -> CodigoError.CUENTA_BLOQUEADA;
                };
                String detalle = codigo == CodigoError.SALDO_INSUFICIENTE ? DETALLE_SALDO : DETALLE_BLOQUEADA;
                yield guardarResultado(llave, id, EstadoTransferencia.RECHAZADA, r.referencia(), detalle, codigo,
                        t -> FabricaProblemas.conTransferencia(codigo, detalle, ruta, id));
            }
        };
    }

    /**
     * Transacción corta: actualiza la transferencia y guarda la respuesta en la llave.
     *
     * @param error {@code null} si es éxito (201); si no, el código de error que define el status
     */
    private RespuestaRegistro guardarResultado(UUID llave, UUID id, EstadoTransferencia estado, String referencia,
                                               String mensaje, CodigoError error,
                                               Function<TransferenciaEntidad, Object> armarCuerpo) {
        int status = error == null ? 201 : error.status().value();
        return tx.execute(e -> {
            TransferenciaEntidad t = transferencias.findById(id).orElseThrow();
            t.registrarResultado(estado, referencia, mensaje, ahora());
            String cuerpo = json(armarCuerpo.apply(t));
            if (llaves.completar(llave, status, cuerpo) == 0) {
                // Solo pasa si otra solicitud la cerró como "atrapada" mientras esperábamos al core.
                log.warn("La llave {} ya estaba cerrada; la transferencia {} se actualizó a {} igualmente",
                        llave, id, estado);
            }
            return new RespuestaRegistro(status, cuerpo, id, false);
        });
    }

    /** El core NO procesó la transferencia: se borra la llave (para poder reintentar) y la transferencia. */
    private void liberar(UUID llave, UUID id) {
        tx.executeWithoutResult(e -> {
            if (llaves.liberar(llave) == 1) {
                transferencias.deleteById(id);
            }
        });
    }

    private static RespuestaRegistro repetida(IdempotenciaEntidad llave) {
        return new RespuestaRegistro(llave.getHttpStatus(), llave.getRespuesta(), llave.getTransferenciaId(), true);
    }

    private String json(Object cuerpo) {
        return jsonMapper.writeValueAsString(cuerpo);
    }

    /** Milisegundos: así la fecha es idéntica en la respuesta del POST y al leerla de PostgreSQL en el GET. */
    private Instant ahora() {
        return reloj.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    // =====================================================================================
    // Consulta
    // =====================================================================================

    @Transactional(readOnly = true)
    public TransferenciaDetalle consultar(UUID id) {
        return transferencias.findById(id)
                .map(ServicioTransferencias::aDetalle)
                .orElseThrow(() -> new TransferenciaNoEncontradaException(id));
    }

    static TransferenciaDetalle aDetalle(TransferenciaEntidad t) {
        return new TransferenciaDetalle(t.getId(), t.getEstado(), t.getReferenciaCore(), t.getMensaje(),
                t.getFechaRegistro(), t.getCuentaOrigen(), t.getCuentaDestino(), t.getBancoDestino(),
                t.getMonto(), t.getMoneda(), t.getConcepto(), t.getFechaSolicitud());
    }
}
