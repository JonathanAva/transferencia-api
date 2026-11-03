package com.izalco.transferencias.core;

import com.izalco.transferencias.core.xml.RespuestaCore;
import com.izalco.transferencias.core.xml.SolicitudCore;
import org.springframework.stereotype.Component;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Convierte entre objetos Java y bytes XML del core usando Jackson 3 XML.
 * Trabaja con bytes (no con String) para que el XML se codifique y decodifique siempre en UTF-8,
 * sin depender del charset que anuncie el core en sus headers.
 */
@Component
public class CodecXmlCore {

    private final XmlMapper xmlMapper = XmlMapper.builder().build();

    public byte[] escribir(SolicitudCore solicitud) {
        return xmlMapper.writeValueAsBytes(solicitud);
    }

    /**
     * Lee la respuesta del core.
     *
     * @throws tools.jackson.core.JacksonException si el XML está mal formado (excepción no verificada en Jackson 3)
     */
    public RespuestaCore leerRespuesta(byte[] xml) {
        return xmlMapper.readValue(xml, RespuestaCore.class);
    }
}
