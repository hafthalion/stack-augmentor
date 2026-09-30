package com.hafnium.stackaugmentor.instrument;

import net.bytebuddy.description.method.ParameterDescription;

/** A parameter whose value is shown after the method name, with its label, and whether the value is shown hashed. */
public record IdParameter(ParameterDescription parameter, String label, boolean hashed) {

    /**
     * The label as the runtime receives it: a hashed parameter's ends with {@code #}, which no parameter name
     * contains, so the bridge between instrumented code and runtime needs no further argument.
     */
    public String encodedLabel() {
        return hashed ? label + "#" : label;
    }
}
