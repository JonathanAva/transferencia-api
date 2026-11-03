package com.izalco.transferencias.core;

import com.izalco.transferencias.core.xml.RespuestaCore;
import com.izalco.transferencias.core.xml.SolicitudCore;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodecXmlCoreTest {

    private final CodecXmlCore codec = new CodecXmlCore();

    @Test
    void escribeExactamenteElXmlDelEnunciado() {
        SolicitudCore solicitud = new SolicitudCore(
                "100200300", "900800700", "BIZ-0042",
                new SolicitudCore.Importe("USD", "150.75"),
                "PAGO DE ALQUILER", "20261001203210");

        String xml = new String(codec.escribir(solicitud), StandardCharsets.UTF_8);

        assertThat(xml).isEqualTo(
                "<TransferenciaRequest>"
                        + "<Origen>100200300</Origen>"
                        + "<Destino>900800700</Destino>"
                        + "<CodigoBanco>BIZ-0042</CodigoBanco>"
                        + "<Importe moneda=\"USD\">150.75</Importe>"
                        + "<Glosa>PAGO DE ALQUILER</Glosa>"
                        + "<FechaHora>20261001203210</FechaHora>"
                        + "</TransferenciaRequest>");
    }

    @Test
    void leeLaRespuestaDelCore() {
        byte[] xml = """
                <TransferenciaResponse>
                  <Codigo>00</Codigo>
                  <Referencia>CORE-123456</Referencia>
                  <Mensaje>APROBADA</Mensaje>
                </TransferenciaResponse>
                """.getBytes(StandardCharsets.UTF_8);

        assertThat(codec.leerRespuesta(xml))
                .isEqualTo(new RespuestaCore("00", "CORE-123456", "APROBADA"));
    }

    @Test
    void ignoraElementosDesconocidosEnLaRespuesta() {
        byte[] xml = "<TransferenciaResponse><Codigo>51</Codigo><Extra>x</Extra></TransferenciaResponse>"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(codec.leerRespuesta(xml).codigo()).isEqualTo("51");
    }

    @Test
    void fallaConXmlMalFormado() {
        byte[] xml = "<TransferenciaResponse><Codigo>00".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> codec.leerRespuesta(xml)).isInstanceOf(JacksonException.class);
    }
}
