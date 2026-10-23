package com.hafnium.stackaugmentor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects the methods and constructors of a class that the annotations augment: their frames show the class's {@link AugmentedReceiver} members and their own
 * {@link AugmentedParam} and {@link AugmentedParams} parameters.
 *
 * <p>The type decides first:
 * <ul>
 *   <li>{@code @Augmented} on the type: all its methods and constructors, except those with {@code @Augmented(false)};</li>
 *   <li>{@code @Augmented(false)} on the type: only the methods and constructors with {@code @Augmented} of their own;</li>
 *   <li>no {@code @Augmented} on the type: none. The type is ignored, and its methods are not looked at, so their
 *       {@code @Augmented} has no effect.</li>
 * </ul>
 *
 * <p>The annotation of the class that declares the method counts; methods that override it are not affected. A Kotlin
 * top-level function takes it on the function itself: the class of its file cannot carry it, and counts as
 * {@code @Augmented(false)}.
 * {@code [augment.receiver]} and {@code [augment.params]} entries, which name members and parameters, do not read it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Augmented {

    /** On a method or constructor, whether it is augmented; on a type, whether all its methods are, or only those marked. */
    boolean value() default true;
}
