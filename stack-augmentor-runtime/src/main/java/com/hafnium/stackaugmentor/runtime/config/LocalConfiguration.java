package com.hafnium.stackaugmentor.runtime.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Added by the build plugin to the classes it instruments when it is given the module's local configuration: the
 * classes then use the {@value AugmentorConfig#LOCAL_RESOURCE} with this content at runtime, wherever on the classpath of
 * their class loader it is. In a build directory, classes and resources are in different directories, so the jar or
 * directory of a class could not find it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LocalConfiguration {

    /** The SHA-256 of the file's content, in lowercase hex: see {@link ClassLoaderConfigs#hash(byte[])}. */
    String value();
}
