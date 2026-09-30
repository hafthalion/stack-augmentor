package com.hafnium.stackaugmentor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shows this parameter's value after the method name in stack traces, e.g. {@code objectMethod{orderId=42}}.
 *
 * <p>The label is the parameter's name as compiled into the class file: compile Java with {@code javac -parameters}
 * (Kotlin: {@code javaParameters = true}), otherwise the label is {@code arg<N>}. To show all parameters of a method,
 * use {@link StackTraceParams}.
 *
 * <p>With {@code secret = true} the value is shown as a short hash instead of its text, e.g.
 * {@code invite{email=#71d4f55f}}: the same value gives the same hash, so it can be followed across log lines
 * without appearing in them.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface StackTraceParam {

    /** Whether the value is shown as a hash of its text instead of the text itself. */
    boolean secret() default false;
}
