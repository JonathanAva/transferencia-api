package com.izalco.transferencias.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica las reglas de validación de la solicitud (Bean Validation), sin levantar Spring.
 */
class SolicitudTransferenciaTest {

    private static ValidatorFactory fabrica;
    private static Validator validador;

    @BeforeAll
    static void crearValidador() {
        fabrica = Validation.buildDefaultValidatorFactory();
        validador = fabrica.getValidator();
    }

    @AfterAll
    static void cerrarValidador() {
        fabrica.close();
    }

    /** Solicitud válida del enunciado; cada prueba cambia un solo campo. */
    static SolicitudTransferencia valida() {
        return new SolicitudTransferencia(
                "100200300", "900800700", "BIZ-0042",
                new BigDecimal("150.75"), "USD", "Pago de alquiler",
                OffsetDateTime.parse("2026-10-01T14:32:10-06:00"));
    }

    @Test
    void laSolicitudDelEnunciadoEsValida() {
        assertThat(validador.validate(valida())).isEmpty();
    }

    /** Ceros a la derecha no son decimales significativos: en JSON 150.750 y 150.75 son el mismo número. */
    @ParameterizedTest(name = "monto {0} es válido")
    @ValueSource(strings = {"150.75", "150.750", "150.7500", "150.7", "100", "100.00", "0.01", "9999999999999.99"})
    void aceptaMontosConMaximoDosDecimalesSignificativos(String monto) {
        assertThat(validador.validate(conMonto(monto))).isEmpty();
    }

    @ParameterizedTest(name = "monto {0} es inválido")
    @ValueSource(strings = {"150.755", "150.7501", "0.001", "10000000000000", "10000000000000.00"})
    void rechazaMontosConMasDeDosDecimalesSignificativosOMasDe13Enteros(String monto) {
        assertThat(validador.validate(conMonto(monto)))
                .extracting(e -> e.getPropertyPath().toString())
                .containsOnly("monto");
    }

    private static SolicitudTransferencia conMonto(String monto) {
        SolicitudTransferencia v = valida();
        return new SolicitudTransferencia(v.cuentaOrigen(), v.cuentaDestino(), v.bancoDestino(),
                new BigDecimal(monto), v.moneda(), v.concepto(), v.fechaSolicitud());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("solicitudesInvalidas")
    void rechazaSolicitudesInvalidas(String caso, SolicitudTransferencia solicitud, String campoEsperado) {
        Set<ConstraintViolation<SolicitudTransferencia>> errores = validador.validate(solicitud);

        assertThat(errores)
                .extracting(e -> e.getPropertyPath().toString())
                .isNotEmpty()
                .containsOnly(campoEsperado);
    }

    static Stream<Arguments> solicitudesInvalidas() {
        SolicitudTransferencia v = valida();
        return Stream.of(
                Arguments.of("cuenta origen con letras",
                        new SolicitudTransferencia("10020030A", v.cuentaDestino(), v.bancoDestino(), v.monto(), v.moneda(), v.concepto(), v.fechaSolicitud()),
                        "cuentaOrigen"),
                Arguments.of("cuenta origen muy corta",
                        new SolicitudTransferencia("12345", v.cuentaDestino(), v.bancoDestino(), v.monto(), v.moneda(), v.concepto(), v.fechaSolicitud()),
                        "cuentaOrigen"),
                Arguments.of("cuenta destino vacía",
                        new SolicitudTransferencia(v.cuentaOrigen(), "", v.bancoDestino(), v.monto(), v.moneda(), v.concepto(), v.fechaSolicitud()),
                        "cuentaDestino"),
                Arguments.of("misma cuenta origen y destino",
                        new SolicitudTransferencia("100200300", "100200300", v.bancoDestino(), v.monto(), v.moneda(), v.concepto(), v.fechaSolicitud()),
                        "cuentasDistintas"),
                Arguments.of("banco con formato inválido",
                        new SolicitudTransferencia(v.cuentaOrigen(), v.cuentaDestino(), "biz-42", v.monto(), v.moneda(), v.concepto(), v.fechaSolicitud()),
                        "bancoDestino"),
                Arguments.of("monto cero",
                        new SolicitudTransferencia(v.cuentaOrigen(), v.cuentaDestino(), v.bancoDestino(), BigDecimal.ZERO, v.moneda(), v.concepto(), v.fechaSolicitud()),
                        "monto"),
                Arguments.of("monto negativo",
                        new SolicitudTransferencia(v.cuentaOrigen(), v.cuentaDestino(), v.bancoDestino(), new BigDecimal("-1.00"), v.moneda(), v.concepto(), v.fechaSolicitud()),
                        "monto"),
                Arguments.of("monto con 3 decimales",
                        new SolicitudTransferencia(v.cuentaOrigen(), v.cuentaDestino(), v.bancoDestino(), new BigDecimal("10.555"), v.moneda(), v.concepto(), v.fechaSolicitud()),
                        "monto"),
                Arguments.of("monto nulo",
                        new SolicitudTransferencia(v.cuentaOrigen(), v.cuentaDestino(), v.bancoDestino(), null, v.moneda(), v.concepto(), v.fechaSolicitud()),
                        "monto"),
                Arguments.of("moneda en minúsculas",
                        new SolicitudTransferencia(v.cuentaOrigen(), v.cuentaDestino(), v.bancoDestino(), v.monto(), "usd", v.concepto(), v.fechaSolicitud()),
                        "moneda"),
                Arguments.of("concepto en blanco",
                        new SolicitudTransferencia(v.cuentaOrigen(), v.cuentaDestino(), v.bancoDestino(), v.monto(), v.moneda(), "   ", v.fechaSolicitud()),
                        "concepto"),
                Arguments.of("concepto de 141 caracteres",
                        new SolicitudTransferencia(v.cuentaOrigen(), v.cuentaDestino(), v.bancoDestino(), v.monto(), v.moneda(), "x".repeat(141), v.fechaSolicitud()),
                        "concepto"),
                Arguments.of("fecha nula",
                        new SolicitudTransferencia(v.cuentaOrigen(), v.cuentaDestino(), v.bancoDestino(), v.monto(), v.moneda(), v.concepto(), null),
                        "fechaSolicitud")
        );
    }
}
