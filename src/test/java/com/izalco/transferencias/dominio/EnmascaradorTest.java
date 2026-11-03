package com.izalco.transferencias.dominio;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

class EnmascaradorTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "100200300, *****0300",
            "900800700, *****0700",
            "12345,     *2345",
            "1234,      ****",
            "12,        **"
    })
    void dejaVisiblesSoloLosUltimosCuatro(String cuenta, String esperado) {
        assertThat(Enmascarador.cuenta(cuenta)).isEqualTo(esperado);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void sinCuentaDevuelveUnaMascaraFija(String cuenta) {
        assertThat(Enmascarador.cuenta(cuenta)).isEqualTo("****");
    }
}
