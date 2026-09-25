package com.hafnium.stackaugmentor.runtime;

/** Selects a parameter of a configured method, by name or by position. */
public sealed interface ParamRef {

    record ByName(String name) implements ParamRef {
    }

    record ByIndex(int index) implements ParamRef {
    }
}
