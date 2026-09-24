package com.hafnium.stackaugmentor.agent.advice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Binds the labels of the values bound by {@link IdArgs}, as constants. {@code null} if the method has none. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface IdArgNames {
}
