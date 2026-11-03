package com.izalco.transferencias.core.xml;

import com.fasterxml.jackson.annotation.JsonRootName;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * Respuesta XML del core. El enunciado no define su formato; supuesto documentado en el README:
 *
 * <pre>{@code
 * <TransferenciaResponse>
 *   <Codigo>00</Codigo>
 *   <Referencia>CORE-123456</Referencia>
 *   <Mensaje>APROBADA</Mensaje>
 * </TransferenciaResponse>
 * }</pre>
 */
@JsonRootName("TransferenciaResponse")
public record RespuestaCore(
        @JacksonXmlProperty(localName = "Codigo") String codigo,
        @JacksonXmlProperty(localName = "Referencia") String referencia,
        @JacksonXmlProperty(localName = "Mensaje") String mensaje
) {
}
