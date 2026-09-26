package com.hafnium.stackaugmentor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shows all parameters after the method name in stack traces, e.g. {@code transfer{from=a, to=b, amount=10}}.
 *
 * <ul>
 *   <li>On a method: all parameters of that method.</li>
 *   <li>On a class: all parameters of every method declared in that class (not of subclasses or nested classes).</li>
 * </ul>
 *
 * <p>Labels are the parameter names; {@link StackTraceParam#name()} on a parameter sets a different one.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface StackTraceParams {
}
