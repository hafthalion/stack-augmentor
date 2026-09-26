package com.hafnium.stackaugmentor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shows this parameter's value after the method name in stack traces, e.g. {@code objectMethod{orderId=42}}.
 *
 * <p>To show all parameters of a method, or of every method of a class, use {@link StackTraceParams}.
 * {@code @StackTraceParam} on one of those parameters still sets its label.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface StackTraceParam {

    /** Label shown in the stack trace; empty means the real parameter name. */
    String name() default "";
}
