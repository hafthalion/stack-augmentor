package com.hafnium.stackaugmentor.instrument.advice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the advice parameter bound to the key of the method's deciding {@code [augment.receiver]} entry where its frames
 * show receiver ids, and to {@code null} elsewhere. See {@link IdReceiverMapping}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface IdReceiverKey {
}
