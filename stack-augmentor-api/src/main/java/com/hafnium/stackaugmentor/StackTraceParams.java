package com.hafnium.stackaugmentor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shows all parameters of the annotated method or constructor after its name in stack traces, e.g.
 * {@code transfer{from=a, to=b, amount=10}} or {@code <init>{orderId=42}}. Methods that override it are not affected.
 *
 * <p>Labels are the parameter names as compiled into the class file (see {@link StackTraceParam}). To show one of
 * these parameters hashed, annotate it with {@code @StackTraceParam(secret = true)}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface StackTraceParams {
}
