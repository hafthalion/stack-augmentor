package com.hafnium.stackaugmentor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or constructor that the configuration selects with {@code "@"} after {@code ::} in a key: in
 * {@code [augment.receiver]}, a {@code "com.acme.Order::@"} key shows the receiver members in the frames of the annotated
 * methods of {@code Order} only, and in {@code [augment.params]}, it matches the annotated methods and constructors of
 * {@code Order}. Methods that override it are not affected.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Augmented {
}
