package com.hafnium.stackaugmentor.instrument;

import net.bytebuddy.description.method.ParameterDescription;

/** A parameter whose value is shown after the method name, with its label. */
public record IdParameter(ParameterDescription parameter, String label) {
}
