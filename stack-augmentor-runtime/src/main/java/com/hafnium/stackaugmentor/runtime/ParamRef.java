package com.hafnium.stackaugmentor.runtime;

/**
 * Selects parameters of a configured method: by name, by position, all of them ({@code "*"}), those its
 * annotations select ({@code "@"}), or none ({@code "-"}).
 */
public sealed interface ParamRef {

    record ByName(String name) implements ParamRef {
    }

    record ByIndex(int index) implements ParamRef {
    }

    record All() implements ParamRef {
    }

    /** {@code "@"}: the method's {@code @StackTraceParam} and {@code @StackTraceParams} annotations. */
    record Annotations() implements ParamRef {
    }

    /** {@code "-"}: no parameters, and less specific entries that match the same method are ignored. */
    record Excluded() implements ParamRef {
    }
}
