package com.hafnium.stackaugmentor.runtime;

/**
 * Selects parameters of a configured method: by name, by position, those its annotations select ({@code "@"}), or
 * none ({@code "-"}). A {@code #} after a name or position ({@code "email#"}, {@code "1#"}) shows the value hashed;
 * the annotations say so themselves, with {@code secret = true}.
 */
public sealed interface ParamRef {

    record ByName(String name, boolean hashed) implements ParamRef {

        public ByName(String name) {
            this(name, false);
        }
    }

    record ByIndex(int index, boolean hashed) implements ParamRef {

        public ByIndex(int index) {
            this(index, false);
        }
    }

    /** {@code "@"}: the method's {@code @StackTraceParam} and {@code @StackTraceParams} annotations. */
    record Annotations() implements ParamRef {
    }

    /** {@code "-"}: no parameters, and less specific entries that match the same method are ignored. */
    record Excluded() implements ParamRef {
    }
}
