package com.hafnium.stackaugmentor.runtime;

/** Selects parameters of a configured method: by name, by position, or all of them ({@code "*"}). */
public sealed interface ParamRef {

    record ByName(String name) implements ParamRef {
    }

    record ByIndex(int index) implements ParamRef {
    }

    record All() implements ParamRef {
    }
}
