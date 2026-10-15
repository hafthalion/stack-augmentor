package com.hafnium.stackaugmentor.runtime.config;

/**
 * Selects parameters of a configured method: by name, by position, or none ({@code "-"}). A {@code #} after a name or
 * position ({@code "email#"}, {@code "1#"}) shows the value hashed.
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

    /** {@code "-"}: no parameters for the methods its key names, even where a later entry would select some. */
    record Excluded() implements ParamRef {
    }
}
