package com.izalco.transferencias.core;

import com.izalco.transferencias.api.SolicitudTransferencia;
import com.izalco.transferencias.core.xml.SolicitudCore;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapeadorCoreTest {

    private final MapeadorCore mapeador = new MapeadorCore();

    @Test
    void mapeaLaSolicitudDelEnunciadoAlFormatoDelCore() {
        SolicitudTransferencia solicitud = new SolicitudTransferencia(
                "100200300", "900800700", "BIZ-0042",
                new BigDecimal("150.75"), "USD", "Pago de alquiler",
                OffsetDateTime.parse("2026-10-01T14:32:10-06:00"));

        SolicitudCore core = mapeador.aCore(solicitud);

        assertThat(core).isEqualTo(new SolicitudCore(
                "100200300", "900800700", "BIZ-0042",
                new SolicitudCore.Importe("USD", "150.75"),
                "PAGO DE ALQUILER",
                "20261001203210"));
    }

    @Nested
    class FechaUtc {

        @Test
        void casoObligatorioDelEnunciado() {
            assertThat(MapeadorCore.formatearFechaUtc(OffsetDateTime.parse("2026-10-01T14:32:10-06:00")))
                    .isEqualTo("20261001203210");
        }

        /**
         * Regresión del incidente INC-2026-118: la transferencia de las 14:32 (-06:00) apareció en el core
         * como 08:32. Ambas hipótesis producen "...083210": formato hh (20:32 → 08) o restar el offset.
         */
        @Test
        void noReproduceElBugDeHoraDelIncidente() {
            assertThat(MapeadorCore.formatearFechaUtc(OffsetDateTime.parse("2026-10-01T14:32:10-06:00")))
                    .doesNotEndWith("083210");
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                // Cruza la medianoche y el cambio de año al convertir a UTC
                "2026-12-31T20:00:00-06:00, 20270101020000",
                // Offset positivo: se resta
                "2026-10-01T14:32:10+02:00, 20261001123210",
                // Ya en UTC: no cambia
                "2026-10-01T14:32:10Z,      20261001143210",
                // Medianoche y mediodía UTC: con hh ambos darían "12"
                "2026-10-01T18:00:00-06:00, 20261002000000",
                "2026-10-01T06:00:00-06:00, 20261001120000"
        })
        void convierteCualquierOffsetAUtcConHoraDe24(String entrada, String esperado) {
            assertThat(MapeadorCore.formatearFechaUtc(OffsetDateTime.parse(entrada))).isEqualTo(esperado);
        }
    }

    @Nested
    class Glosa {

        @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
        @CsvSource(delimiter = '|', value = {
                "Pago de alquiler                     | PAGO DE ALQUILER",
                "Pagó cuota del ñandú                 | PAGO CUOTA DEL NANDU",
                "  Pago   con    espacios   raros     | PAGO CON ESPACIOS RAROS",
                "Cumpleaños 🎉 de Ana                 | CUMPLEANOS DE ANA",
                "Ünico pago Ç                         | UNICO PAGO C"
        })
        void pasaAMayusculasSinTildesNiCaracteresNoAscii(String concepto, String esperado) {
            assertThat(MapeadorCore.normalizarGlosa(concepto)).isEqualTo(esperado);
        }

        @Test
        void truncaA30Caracteres() {
            String glosa = MapeadorCore.normalizarGlosa("Pago de alquiler del mes de octubre de 2026");

            assertThat(glosa)
                    .hasSize(30)
                    .isEqualTo("PAGO DE ALQUILER DEL MES DE OC");
        }

        @Test
        void noDejaEspacioFinalAlTruncar() {
            // El carácter 30 es un espacio: se recorta para no enviar "...ABCD " al core
            String glosa = MapeadorCore.normalizarGlosa("a".repeat(29) + " bbbb");

            assertThat(glosa).isEqualTo("A".repeat(29));
        }

        @Test
        void usaGlosaPorDefectoSiNoQuedaTextoAscii() {
            assertThat(MapeadorCore.normalizarGlosa("🎉🎉🎉")).isEqualTo("TRANSFERENCIA");
        }

        /**
         * Con el idioma turco, "i".toUpperCase() da "İ" (I con punto, no ASCII).
         * Locale.ROOT hace que el resultado no dependa del idioma configurado en el servidor.
         */
        @Test
        void noDependeDelIdiomaDelServidor() {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                assertThat(MapeadorCore.normalizarGlosa("izalco digital")).isEqualTo("IZALCO DIGITAL");
            } finally {
                Locale.setDefault(original);
            }
        }
    }

    @Nested
    class Monto {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "150.75,           150.75",
                "100,              100.00",
                "150.7,            150.70",
                "0.01,             0.01",
                "1234567890123.45, 1234567890123.45",
                "1E+3,             1000.00"
        })
        void formateaConDosDecimalesSinNotacionCientifica(String monto, String esperado) {
            assertThat(MapeadorCore.formatearMonto(new BigDecimal(monto))).isEqualTo(esperado);
        }

        @Test
        void fallaEnVezDeRedondearEnSilencio() {
            assertThatThrownBy(() -> MapeadorCore.formatearMonto(new BigDecimal("10.555")))
                    .isInstanceOf(ArithmeticException.class);
        }
    }
}
