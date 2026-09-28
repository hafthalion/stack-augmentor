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
 * or of every method of a class, use {@link StackTraceParams}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface StackTraceParam {
}
