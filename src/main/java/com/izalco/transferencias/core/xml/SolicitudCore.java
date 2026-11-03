package com.izalco.transferencias.core.xml;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonRootName;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlText;

/**
 * Solicitud en el formato XML que espera el core legado:
 *
 * <pre>{@code
 * <TransferenciaRequest>
 *   <Origen>100200300</Origen>
 *   <Destino>900800700</Destino>
 *   <CodigoBanco>BIZ-0042</CodigoBanco>
 *   <Importe moneda="USD">150.75</Importe>
 *   <Glosa>PAGO DE ALQUILER</Glosa>
 *   <FechaHora>20261001203210</FechaHora>
 * </TransferenciaRequest>
 * }</pre>
 */
@JsonRootName("TransferenciaRequest")
@JsonPropertyOrder({"origen", "destino", "codigoBanco", "importe", "glosa", "fechaHora"})
public record SolicitudCore(
        @JacksonXmlProperty(localName = "Origen") String origen,
        @JacksonXmlProperty(localName = "Destino") String destino,
        @JacksonXmlProperty(localName = "CodigoBanco") String codigoBanco,
        @JacksonXmlProperty(localName = "Importe") Importe importe,
        @JacksonXmlProperty(localName = "Glosa") String glosa,
        @JacksonXmlProperty(localName = "FechaHora") String fechaHora
) {

    /**
     * {@code <Importe moneda="USD">150.75</Importe>}: la moneda es un atributo y el monto es el texto.
     * El monto viaja como texto ya formateado con 2 decimales para controlar exactamente lo que recibe el core.
     */
    public record Importe(
            @JacksonXmlProperty(isAttribute = true, localName = "moneda") String moneda,
            @JacksonXmlText String valor
    ) {
    }
}
