package com.izalco.transferencias.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;

/**
 * Monto con máximo 13 dígitos enteros y 2 decimales <b>significativos</b>.
 * <p>
 * A diferencia de {@code @Digits}, que cuenta los decimales <i>escritos</i>, aquí se quitan primero los
 * ceros a la derecha: en JSON {@code 150.750} y {@code 150.75} son el mismo número, así que se acepta;
 * {@code 150.755} tiene 3 decimales significativos y se rechaza. Un {@code null} lo informa {@code @NotNull}.
 */
@Documented
@Constraint(validatedBy = MontoValido.Validador.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface MontoValido {

    int MAXIMO_ENTEROS = 13;
    int MAXIMO_DECIMALES = 2;

    String message() default "debe tener máximo 13 enteros y 2 decimales";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validador implements ConstraintValidator<MontoValido, BigDecimal> {

        @Override
        public boolean isValid(BigDecimal monto, ConstraintValidatorContext contexto) {
            if (monto == null) {
                return true;
            }
            BigDecimal normalizado = monto.stripTrailingZeros();
            int decimales = Math.max(normalizado.scale(), 0);
            int enteros = normalizado.precision() - normalizado.scale();
            return decimales <= MAXIMO_DECIMALES && enteros <= MAXIMO_ENTEROS;
        }
    }
}
