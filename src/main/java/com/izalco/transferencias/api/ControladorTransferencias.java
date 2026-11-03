package com.izalco.transferencias.api;

import com.izalco.transferencias.dominio.RespuestaRegistro;
import com.izalco.transferencias.dominio.ServicioTransferencias;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Endpoints de {@code /transferencias}, según el contrato {@code openapi/transferencias.yaml}.
 */
@RestController
@RequestMapping("/transferencias")
public class ControladorTransferencias {

    static final String HEADER_REPETIDA = "Idempotent-Replayed";

    private final ServicioTransferencias servicio;

    public ControladorTransferencias(ServicioTransferencias servicio) {
        this.servicio = servicio;
    }

    /**
     * {@code POST /transferencias}.
     * <p>
     * El header se declara antes que el cuerpo: Spring resuelve los parámetros en orden, así que una llave
     * ausente o inválida se informa (400 IDEMPOTENCY_KEY_REQUERIDA) antes que los errores del cuerpo.
     * <p>
     * La respuesta se devuelve como bytes ya serializados: es exactamente el JSON guardado para la
     * idempotencia, así un reintento recibe una respuesta idéntica byte por byte.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> registrar(@RequestHeader("X-Idempotency-Key") UUID llave,
                                            @Valid @RequestBody SolicitudTransferencia solicitud,
                                            HttpServletRequest request) {
        RespuestaRegistro respuesta = servicio.registrar(llave, solicitud, request.getRequestURI());

        boolean creada = respuesta.status() == HttpStatus.CREATED.value();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(respuesta.status())
                .contentType(creada ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_PROBLEM_JSON);
        if (creada) {
            builder.location(URI.create("/transferencias/" + respuesta.transferenciaId()));
        }
        if (respuesta.repetida()) {
            builder.header(HEADER_REPETIDA, "true");
        }
        return builder.body(respuesta.cuerpo().getBytes(StandardCharsets.UTF_8));
    }

    /** {@code GET /transferencias/{id}}: 200, 400 (id no es UUID) o 404. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TransferenciaDetalle consultar(@PathVariable("id") UUID id) {
        return servicio.consultar(id);
    }
}
