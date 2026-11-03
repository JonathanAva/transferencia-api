package com.izalco.transferencias.api;

import com.izalco.transferencias.core.CoreNoDisponibleException;
import com.izalco.transferencias.dominio.TransferenciaNoEncontradaException;
import com.izalco.transferencias.idempotencia.LlaveReutilizadaException;
import com.izalco.transferencias.idempotencia.SolicitudEnProcesoException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Traduce toda excepción a un Problem Details con {@code codigo} y {@code correlationId}.
 * <p>
 * Hereda de {@link ResponseEntityExceptionHandler} para cubrir también los errores propios de Spring
 * (JSON mal formado, método no permitido, ruta inexistente...): todos pasan por
 * {@link #handleExceptionInternal}, que les agrega los campos del contrato.
 */
@RestControllerAdvice
public class ManejadorErrores extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    static final String HEADER_IDEMPOTENCIA = "X-Idempotency-Key";

    /** Sugerencia a la app (header Retry-After) cuando el core no está disponible. */
    static final int SEGUNDOS_REINTENTO = 30;

    // ---------- Errores de negocio ----------

    @ExceptionHandler(TransferenciaNoEncontradaException.class)
    ResponseEntity<ProblemDetail> noEncontrada(TransferenciaNoEncontradaException ex, HttpServletRequest req) {
        return responder(FabricaProblemas.crear(CodigoError.TRANSFERENCIA_NO_ENCONTRADA,
                ex.getMessage() + ".", req.getRequestURI()));
    }

    @ExceptionHandler(LlaveReutilizadaException.class)
    ResponseEntity<ProblemDetail> llaveReutilizada(LlaveReutilizadaException ex, HttpServletRequest req) {
        return responder(FabricaProblemas.crear(CodigoError.IDEMPOTENCY_KEY_REUTILIZADA,
                ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(SolicitudEnProcesoException.class)
    ResponseEntity<ProblemDetail> enProceso(SolicitudEnProcesoException ex, HttpServletRequest req) {
        return responder(FabricaProblemas.crear(CodigoError.SOLICITUD_EN_PROCESO,
                ex.getMessage(), req.getRequestURI()));
    }

    /** El core no procesó la transferencia: la app puede reintentar con la misma llave. */
    @ExceptionHandler(CoreNoDisponibleException.class)
    ResponseEntity<ProblemDetail> coreNoDisponible(CoreNoDisponibleException ex, HttpServletRequest req) {
        log.warn("Core no disponible: {}", ex.getMessage());
        ProblemDetail problema = FabricaProblemas.crear(CodigoError.CORE_NO_DISPONIBLE,
                "El sistema central no está disponible en este momento. Intente más tarde.", req.getRequestURI());
        return ResponseEntity.status(problema.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(SEGUNDOS_REINTENTO))
                .body(problema);
    }

    /** Último recurso: nunca expone detalles técnicos al cliente, pero sí los deja en el log. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> inesperado(Exception ex, HttpServletRequest req) {
        log.error("Error inesperado en {} {}", req.getMethod(), req.getRequestURI(), ex);
        return responder(FabricaProblemas.crear(CodigoError.ERROR_INTERNO,
                "Ocurrió un error inesperado. Reporte el correlationId a soporte.", req.getRequestURI()));
    }

    // ---------- Errores de Spring MVC ----------

    /** Falla de Bean Validation en el cuerpo ({@code @Valid}). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FabricaProblemas.ErrorCampo> errores = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FabricaProblemas.ErrorCampo(e.getField(), mensaje(e)))
                .sorted(Comparator.comparing(FabricaProblemas.ErrorCampo::campo)
                        .thenComparing(FabricaProblemas.ErrorCampo::mensaje))
                .toList();
        String detalle = errores.size() == 1
                ? "La solicitud tiene 1 campo inválido."
                : "La solicitud tiene " + errores.size() + " campos inválidos.";
        return conCuerpo(ex, FabricaProblemas.conErrores(detalle, ruta(request), errores), headers, request);
    }

    /** JSON mal formado, campo desconocido o valor con tipo/formato incorrecto (p. ej. fecha sin zona horaria). */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String campo = campoConError(ex);
        ProblemDetail problema = campo == null
                ? FabricaProblemas.crear(CodigoError.SOLICITUD_INVALIDA,
                        "El cuerpo de la solicitud no es un JSON válido.", ruta(request))
                : FabricaProblemas.conErrores("El campo '" + campo + "' no es válido o no está permitido.",
                        ruta(request), List.of(new FabricaProblemas.ErrorCampo(campo,
                                "valor con formato incorrecto o campo no permitido")));
        return conCuerpo(ex, problema, headers, request);
    }

    /** Falta un header obligatorio: aquí solo puede ser X-Idempotency-Key. */
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MissingRequestHeaderException m && HEADER_IDEMPOTENCIA.equalsIgnoreCase(m.getHeaderName())) {
            return conCuerpo(ex, llaveRequerida(request), headers, request);
        }
        return super.handleServletRequestBindingException(ex, headers, status, request);
    }

    /** Un header o parámetro de ruta no se pudo convertir a UUID. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (ex instanceof MethodArgumentTypeMismatchException m) {
            if (HEADER_IDEMPOTENCIA.equalsIgnoreCase(m.getName())) {
                return conCuerpo(ex, llaveRequerida(request), headers, request);
            }
            return conCuerpo(ex, FabricaProblemas.conErrores(
                    "El parámetro '" + m.getName() + "' no tiene un formato válido.", ruta(request),
                    List.of(new FabricaProblemas.ErrorCampo(m.getName(), "debe ser un UUID válido"))),
                    headers, request);
        }
        return super.handleTypeMismatch(ex, headers, status, request);
    }

    /**
     * Punto por el que pasan todos los errores de Spring. Si el cuerpo aún no tiene {@code codigo}
     * (por ejemplo, 404 de ruta inexistente o 405), se le agregan los campos del contrato.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        // Spring llama aquí con body == null en varios errores (404, 405...) y recién después arma el
        // ProblemDetail a partir de la excepción. Se toma ese cuerpo para poder enriquecerlo.
        if (body == null && ex instanceof ErrorResponse respuestaDeError) {
            body = respuestaDeError.getBody();
        }
        if (body instanceof ProblemDetail original
                && (original.getProperties() == null || !original.getProperties().containsKey("codigo"))) {
            CodigoError codigo = statusCode.is5xxServerError() ? CodigoError.ERROR_INTERNO : CodigoError.SOLICITUD_INVALIDA;
            ProblemDetail enriquecido = FabricaProblemas.crear(codigo,
                    Objects.requireNonNullElse(original.getDetail(), codigo.titulo()), ruta(request));
            enriquecido.setStatus(statusCode.value());
            enriquecido.setTitle(Objects.requireNonNullElse(original.getTitle(), codigo.titulo()));
            body = enriquecido;
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    // ---------- Auxiliares ----------

    private ResponseEntity<Object> conCuerpo(Exception ex, ProblemDetail problema, HttpHeaders headers,
                                             WebRequest request) {
        return super.handleExceptionInternal(ex, problema, headers,
                HttpStatusCode.valueOf(problema.getStatus()), request);
    }

    private static ResponseEntity<ProblemDetail> responder(ProblemDetail problema) {
        return ResponseEntity.status(problema.getStatus()).body(problema);
    }

    private static ProblemDetail llaveRequerida(WebRequest request) {
        return FabricaProblemas.crear(CodigoError.IDEMPOTENCY_KEY_REQUERIDA,
                "El header X-Idempotency-Key es obligatorio y debe ser un UUID.", ruta(request));
    }

    private static String ruta(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }

    private static String mensaje(FieldError error) {
        return Objects.requireNonNullElse(error.getDefaultMessage(), "valor inválido");
    }

    /** Nombre del campo JSON que causó el error de lectura, si Jackson lo informa. */
    private static String campoConError(HttpMessageNotReadableException ex) {
        // Se recorre la cadena de causas: la más profunda puede ser, p. ej., DateTimeParseException,
        // pero la que conoce el nombre del campo es la DatabindException de Jackson que la envuelve.
        for (Throwable causa = ex.getCause(); causa != null; causa = causa.getCause()) {
            if (causa instanceof DatabindException e && !e.getPath().isEmpty()) {
                String campo = e.getPath().stream()
                        .map(JacksonException.Reference::getPropertyName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("."));
                return campo.isEmpty() ? null : campo;
            }
        }
        return null;
    }
}
