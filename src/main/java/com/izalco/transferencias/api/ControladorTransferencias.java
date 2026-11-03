package com.izalco.transferencias.api;

import com.izalco.transferencias.dominio.ServicioTransferencias;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Endpoints de {@code /transferencias}, según el contrato {@code openapi/transferencias.yaml}.
 */
@RestController
@RequestMapping("/transferencias")
public class ControladorTransferencias {

    private final ServicioTransferencias servicio;

    public ControladorTransferencias(ServicioTransferencias servicio) {
        this.servicio = servicio;
    }

    /** {@code GET /transferencias/{id}}: 200, 400 (id no es UUID) o 404. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TransferenciaDetalle consultar(@PathVariable("id") UUID id) {
        return servicio.consultar(id);
    }
}
