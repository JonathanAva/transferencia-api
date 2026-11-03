package com.izalco.transferencias.core;

import com.izalco.transferencias.core.ResultadoCore.MotivoRechazo;
import com.izalco.transferencias.core.xml.RespuestaCore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraductorRespuestaCoreTest {

    private final TraductorRespuestaCore traductor = new TraductorRespuestaCore();

    @Test
    void codigo00EsAprobada() {
        ResultadoCore resultado = traductor.traducir(new RespuestaCore("00", "CORE-123456", "APROBADA"));

        assertThat(resultado).isEqualTo(new ResultadoCore.Aprobada("CORE-123456", "APROBADA"));
    }

    @Test
    void codigo51EsRechazoPorSaldoInsuficiente() {
        ResultadoCore resultado = traductor.traducir(new RespuestaCore("51", "CORE-1", "SALDO INSUFICIENTE"));

        assertThat(resultado).isEqualTo(
                new ResultadoCore.Rechazada(MotivoRechazo.SALDO_INSUFICIENTE, "CORE-1", "SALDO INSUFICIENTE"));
    }

    @Test
    void codigo62EsRechazoPorCuentaBloqueada() {
        ResultadoCore resultado = traductor.traducir(new RespuestaCore("62", "CORE-2", "CUENTA BLOQUEADA"));

        assertThat(resultado).isEqualTo(
                new ResultadoCore.Rechazada(MotivoRechazo.CUENTA_BLOQUEADA, "CORE-2", "CUENTA BLOQUEADA"));
    }

    @Test
    void toleraEspaciosAlrededorDelCodigo() {
        assertThat(traductor.traducir(new RespuestaCore(" 00 ", "CORE-3", null)))
                .isInstanceOf(ResultadoCore.Aprobada.class);
    }

    @Test
    void codigo91EsCoreNoDisponibleYNoSeProceso() {
        assertThatThrownBy(() -> traductor.traducir(new RespuestaCore("91", null, "NO DISPONIBLE")))
                .isInstanceOfSatisfying(CoreNoDisponibleException.class,
                        e -> assertThat(e.puedeHaberseProcesado()).isFalse());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "99", "0", "000", "AB"})
    void codigoDesconocidoEsRespuestaInvalidaConResultadoIncierto(String codigo) {
        assertThatThrownBy(() -> traductor.traducir(new RespuestaCore(codigo, null, null)))
                .isInstanceOfSatisfying(RespuestaCoreInvalidaException.class,
                        e -> assertThat(e.puedeHaberseProcesado()).isTrue());
    }
}
