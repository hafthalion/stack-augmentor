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
 * <ul>
 *   <li>On a method or constructor, it decides for that one: {@code @Augmented} on, {@code @Augmented(false)} off.</li>
 *   <li>On a type, {@code @Augmented} turns them on for the methods and constructors without one of their own.
 *       {@code @Augmented(false)} belongs on methods and constructors only: on a type it is the same as none, with a
 *       warning.</li>
 *   <li>Without either, the method or constructor is not augmented: a class without {@code @Augmented} is ignored, and
 *       its other annotations are not looked for.</li>
 * </ul>
 *
 * <p>The annotation of the class that declares the method counts; methods that override it are not affected.
 * {@code [augment.receiver]} and {@code [augment.params]} entries, which name members and parameters, do not read it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Augmented {

    /** Whether the annotations augment the method or constructor; {@code false} belongs on methods and constructors only. */
    boolean value() default true;
}
