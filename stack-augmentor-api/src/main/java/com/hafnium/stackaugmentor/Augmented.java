package com.hafnium.stackaugmentor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects which methods and constructors of a class the annotations augment, where {@code [augment.annotations]}
 * selects the class: their frames show the class's {@link AugmentedReceiver} members and their own
 * {@link AugmentedParam} and {@link AugmentedParams} parameters.
 *
 * <ul>
 *   <li>On a method or constructor, it decides for that one: {@code @Augmented} on, {@code @Augmented(false)} off.</li>
 *   <li>On a type, it decides for the methods and constructors without one of their own: {@code @Augmented(false)}
 *       turns the annotations of the class off.</li>
 *   <li>Without either, all of them are augmented, unless another method or constructor of the class has
 *       {@code @Augmented}: then only those are.</li>
 * </ul>
 *
 * <p>The annotation of the class that declares the method counts; methods that override it are not affected.
 * {@code [augment.receiver]} and {@code [augment.params]} entries, which name members and parameters, do not read it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Augmented {

    /** Whether the annotations augment the method, or the methods of the type. */
    boolean value() default true;
}
