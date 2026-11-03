package com.izalco.transferencias.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Asigna a cada solicitud un identificador de correlación para rastrearla en los logs de todos los sistemas.
 * <ul>
 *   <li>Si llega {@code X-Correlation-Id} válido, se reutiliza; si no llega o es inválido, se genera un UUID.</li>
 *   <li>Se guarda en el MDC (contexto de logs del hilo) y se devuelve siempre en la respuesta.</li>
 * </ul>
 * Corre primero ({@code HIGHEST_PRECEDENCE}) para que todo lo demás, incluidos los errores, ya lo tenga.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCorrelationId extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String CLAVE_MDC = "correlationId";

    /**
     * Mismo patrón que el contrato. Impide meter saltos de línea u otros caracteres en los logs (log injection).
     */
    private static final Pattern VALIDO = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String recibido = request.getHeader(HEADER);
        String correlationId = (recibido != null && VALIDO.matcher(recibido).matches())
                ? recibido
                : UUID.randomUUID().toString();

        MDC.put(CLAVE_MDC, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Los hilos del servidor se reutilizan: sin esto, el id "se filtraría" a la siguiente solicitud.
            MDC.remove(CLAVE_MDC);
        }
    }

    /** Correlation id de la solicitud en curso. */
    public static String actual() {
        String correlationId = MDC.get(CLAVE_MDC);
        return correlationId != null ? correlationId : "desconocido";
    }
}
