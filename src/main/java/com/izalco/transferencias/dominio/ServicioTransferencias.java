package com.izalco.transferencias.dominio;

import com.izalco.transferencias.api.TransferenciaDetalle;
import com.izalco.transferencias.persistencia.TransferenciaEntidad;
import com.izalco.transferencias.persistencia.TransferenciaRepositorio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Casos de uso de transferencias.
 */
@Service
public class ServicioTransferencias {

    private final TransferenciaRepositorio transferencias;

    public ServicioTransferencias(TransferenciaRepositorio transferencias) {
        this.transferencias = transferencias;
    }

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
