package com.izalco.transferencias.core;

import com.izalco.transferencias.api.SolicitudTransferencia;
import com.izalco.transferencias.core.xml.RespuestaCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Envía una transferencia al core legado y devuelve su resultado de negocio.
 * <p>
 * <b>Nunca reintenta</b>: el core no es idempotente y un reintento automático podría duplicar la
 * transferencia. Decidir si se puede reintentar le corresponde a quien llama, usando
 * {@link FallaCoreException#puedeHaberseProcesado()}.
 */
@Component
public class ClienteCore {

    private static final Logger log = LoggerFactory.getLogger(ClienteCore.class);

    private static final MediaType XML_UTF8 = new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8);

    private final RestClient restClient;
    private final MapeadorCore mapeador;
    private final CodecXmlCore codec;
    private final TraductorRespuestaCore traductor;

    public ClienteCore(RestClient restClient, MapeadorCore mapeador,
                       CodecXmlCore codec, TraductorRespuestaCore traductor) {
        this.restClient = restClient;
        this.mapeador = mapeador;
        this.codec = codec;
        this.traductor = traductor;
    }

    /**
     * @return {@link ResultadoCore.Aprobada} o {@link ResultadoCore.Rechazada}
     * @throws CoreNoDisponibleException     el core no procesó la solicitud (seguro reintentar)
     * @throws CoreTimeoutException          el core no respondió a tiempo (resultado incierto)
     * @throws RespuestaCoreInvalidaException respuesta no interpretable (resultado incierto)
     */
    public ResultadoCore enviar(SolicitudTransferencia solicitud) {
        byte[] xmlSolicitud = codec.escribir(mapeador.aCore(solicitud));
        byte[] xmlRespuesta = llamarAlCore(xmlSolicitud);
        return traductor.traducir(leer(xmlRespuesta));
    }

    private byte[] llamarAlCore(byte[] xmlSolicitud) {
        try {
            byte[] cuerpo = restClient.post()
                    .contentType(XML_UTF8)
                    .accept(MediaType.APPLICATION_XML)
                    .body(xmlSolicitud)
                    .retrieve()
                    .body(byte[].class);
            if (cuerpo == null || cuerpo.length == 0) {
                throw new RespuestaCoreInvalidaException("El core respondió sin cuerpo");
            }
            return cuerpo;
        } catch (ResourceAccessException e) {
            // Error de red (IOException envuelta por Spring): hay que ver en qué momento ocurrió.
            FallaCoreException falla = clasificarErrorDeRed(e);
            log.warn("Falla de comunicación con el core: {} (¿pudo procesarse? {})",
                    falla.getMessage(), falla.puedeHaberseProcesado());
            throw falla;
        } catch (RestClientResponseException e) {
            // El core recibió la solicitud y respondió con HTTP 4xx/5xx: no sabemos si la procesó.
            throw new RespuestaCoreInvalidaException(
                    "El core respondió HTTP " + e.getStatusCode().value(), e);
        }
    }

    private RespuestaCore leer(byte[] xmlRespuesta) {
        try {
            return codec.leerRespuesta(xmlRespuesta);
        } catch (JacksonException e) {
            throw new RespuestaCoreInvalidaException("El core respondió un XML inválido", e);
        }
    }

    /**
     * Decide si un error de red ocurrió <b>antes</b> o <b>después</b> de que la solicitud llegara al core.
     * <ul>
     *   <li>{@link HttpConnectTimeoutException} o {@link ConnectException}: no se pudo conectar, la
     *       solicitud nunca se envió → {@link CoreNoDisponibleException}.</li>
     *   <li>{@link HttpTimeoutException} (sin "Connect"): ya conectados, la respuesta no llegó a tiempo
     *       → {@link CoreTimeoutException}.</li>
     *   <li>Cualquier otro error de E/S (conexión cortada a mitad de la respuesta, etc.): la solicitud
     *       pudo haber llegado → {@link RespuestaCoreInvalidaException}.</li>
     * </ul>
     * <b>El orden importa</b>: {@code HttpConnectTimeoutException} es subclase de {@code HttpTimeoutException},
     * así que se debe preguntar primero por la de conexión.
     */
    static FallaCoreException clasificarErrorDeRed(ResourceAccessException error) {
        for (Throwable causa = error.getCause(); causa != null; causa = causa.getCause()) {
            if (causa instanceof HttpConnectTimeoutException || causa instanceof ConnectException) {
                return new CoreNoDisponibleException(
                        "No se pudo conectar con el core: " + causa.getMessage(), error);
            }
            if (causa instanceof HttpTimeoutException) {
                return new CoreTimeoutException(
                        "El core no respondió dentro del timeout de lectura", error);
            }
        }
        return new RespuestaCoreInvalidaException(
                "Se interrumpió la comunicación con el core: " + error.getMessage(), error);
    }
}
