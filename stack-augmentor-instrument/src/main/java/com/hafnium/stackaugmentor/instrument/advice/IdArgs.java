package com.hafnium.stackaugmentor.instrument.advice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the values of all id parameters of the instrumented method, in declaration order,
 * boxing primitives. {@code null} if the method has none.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface IdArgs {
}
