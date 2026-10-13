package com.hafnium.stackaugmentor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or constructor that the configuration selects with {@code "@"} after {@code ::}: in
 * {@code [augment.receiver]}, {@code "id :: @"} shows the receiver ids in the frames of the annotated methods only, and
 * in {@code [augment.params]}, a {@code "com.acme.Order::@"} key matches the annotated methods and constructors of
 * {@code Order}. Methods that override it are not affected.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface StackTraceAugment {
}
