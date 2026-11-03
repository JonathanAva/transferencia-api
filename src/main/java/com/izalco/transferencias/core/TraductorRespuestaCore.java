package com.izalco.transferencias.core;

import com.izalco.transferencias.core.ResultadoCore.MotivoRechazo;
import com.izalco.transferencias.core.xml.RespuestaCore;
import org.springframework.stereotype.Component;

/**
 * Traduce el código de respuesta del core a un resultado de negocio.
 *
 * <table>
 *   <tr><th>Código</th><th>Resultado</th></tr>
 *   <tr><td>00</td><td>{@link ResultadoCore.Aprobada}</td></tr>
 *   <tr><td>51</td><td>{@link ResultadoCore.Rechazada} SALDO_INSUFICIENTE</td></tr>
 *   <tr><td>62</td><td>{@link ResultadoCore.Rechazada} CUENTA_BLOQUEADA</td></tr>
 *   <tr><td>91</td><td>{@link CoreNoDisponibleException}</td></tr>
 *   <tr><td>otro / vacío</td><td>{@link RespuestaCoreInvalidaException}</td></tr>
 * </table>
 */
@Component
public class TraductorRespuestaCore {

    public ResultadoCore traducir(RespuestaCore respuesta) {
        String codigo = respuesta.codigo() == null ? "" : respuesta.codigo().strip();

        return switch (codigo) {
            case "00" -> new ResultadoCore.Aprobada(respuesta.referencia(), respuesta.mensaje());
            case "51" -> new ResultadoCore.Rechazada(
                    MotivoRechazo.SALDO_INSUFICIENTE, respuesta.referencia(), respuesta.mensaje());
            case "62" -> new ResultadoCore.Rechazada(
                    MotivoRechazo.CUENTA_BLOQUEADA, respuesta.referencia(), respuesta.mensaje());
            case "91" -> throw new CoreNoDisponibleException("El core respondió código 91 (no disponible)", null);
            default -> throw new RespuestaCoreInvalidaException(
                    "El core respondió un código desconocido: '" + codigo + "'");
        };
    }
}
