package com.hafnium.stackaugmentor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the source of the id of an object, shown in stack traces next to the class name of every frame
 * running on that object, e.g. {@code ObjectClass{objectId=object-1}.objectMethod}.
 *
 * <p>Put it on a field or a no-argument method. In Kotlin, a {@code val} declared in the primary constructor
 * is also recognised. For method parameters, use {@link StackTraceParam} and {@link StackTraceParams}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface StackTraceId {

    /** Label shown in the stack trace; empty means the real field or method name. */
    String name() default "";
}
