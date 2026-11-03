package com.izalco.transferencias.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuración del cliente del core (prefijo {@code core} en application.yaml).
 * <p>
 * {@code @Validated}: si falta un valor o es inválido, la aplicación <b>no arranca</b> (fail fast).
 * Es mejor que falle el despliegue a que la API funcione mal en producción.
 *
 * @param url             URL completa del endpoint de transferencias del core
 * @param usuario         usuario de servicio del core (variable de entorno, nunca en el repositorio)
 * @param password        contraseña del usuario de servicio (variable de entorno, nunca en el repositorio)
 * @param timeoutConexion tiempo máximo para <b>establecer</b> la conexión TCP. Si se agota, la solicitud
 *                        no llegó al core: es seguro reintentar
 * @param timeoutLectura  tiempo máximo para <b>recibir</b> la respuesta completa. Si se agota, el core pudo
 *                        haber procesado la transferencia. Debe ser mayor que el p95 real del core
 */
@Validated
@ConfigurationProperties(prefix = "core")
public record CoreProperties(
        @NotBlank
        @Pattern(regexp = "^https?://\\S+$", message = "debe ser una URL http(s) completa")
        String url,

        @NotBlank
        String usuario,

        @NotBlank
        String password,

        @NotNull
        @DurationMin(millis = 1)
        Duration timeoutConexion,

        @NotNull
        @DurationMin(millis = 1)
        Duration timeoutLectura
) {

    /**
     * El reloj del timeout de lectura empieza a correr junto con la conexión. Si el de conexión no es
     * menor, una conexión lenta se reportaría como timeout de lectura (resultado "incierto") cuando en
     * realidad la solicitud nunca llegó al core.
     */
    @AssertTrue(message = "core.timeout-conexion debe ser menor que core.timeout-lectura")
    public boolean isTimeoutsCoherentes() {
        return timeoutConexion == null || timeoutLectura == null
                || timeoutConexion.compareTo(timeoutLectura) < 0;
    }

    /** Evita que la contraseña aparezca en logs o mensajes de error. */
    @Override
    public String toString() {
        return "CoreProperties[url=" + url + ", usuario=" + usuario + ", password=****"
                + ", timeoutConexion=" + timeoutConexion + ", timeoutLectura=" + timeoutLectura + "]";
    }
}
