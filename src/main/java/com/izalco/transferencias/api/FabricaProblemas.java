package com.izalco.transferencias.api;

import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Construye los errores en formato Problem Details (RFC 9457) con las extensiones del contrato:
 * {@code codigo}, {@code correlationId} y, cuando aplica, {@code transferenciaId} o {@code errores}.
 * Es el único lugar donde se arma un error, para que todos tengan exactamente la misma forma.
 */
public final class FabricaProblemas {

    static final String BASE_TIPO = "https://api.izalco-digital.example/problemas/";

    private FabricaProblemas() {
    }

    public static ProblemDetail crear(CodigoError codigo, String detalle, String ruta) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(codigo.status(), detalle);
        problema.setType(URI.create(BASE_TIPO + codigo.slug()));
        problema.setTitle(codigo.titulo());
        problema.setInstance(URI.create(ruta));
        problema.setProperty("codigo", codigo.name());
        problema.setProperty("correlationId", FiltroCorrelationId.actual());
        return problema;
    }

    public static ProblemDetail conTransferencia(CodigoError codigo, String detalle, String ruta,
                                                 UUID transferenciaId) {
        ProblemDetail problema = crear(codigo, detalle, ruta);
        problema.setProperty("transferenciaId", transferenciaId);
        return problema;
    }

    public static ProblemDetail conErrores(String detalle, String ruta, List<ErrorCampo> errores) {
        ProblemDetail problema = crear(CodigoError.SOLICITUD_INVALIDA, detalle, ruta);
        problema.setProperty("errores", errores);
        return problema;
    }

    /** Un campo inválido dentro de {@code errores}. */
    public record ErrorCampo(String campo, String mensaje) {
    }
}
