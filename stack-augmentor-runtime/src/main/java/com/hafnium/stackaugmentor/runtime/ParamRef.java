package com.hafnium.stackaugmentor.runtime;

/**
 * Selects parameters of a configured method: by name, by position, all of them ({@code "*"}), those its
 * annotations select ({@code "@"}), or none ({@code "-"}). A {@code #} after the selector hashes the values, and
 * {@code #?} after {@code "*"} or {@code "@"} hashes only those whose names look sensitive, see {@link Hashing}.
 */
public sealed interface ParamRef {

    /** How the values of the selected parameters are shown. */
    enum Hashing {
        /** As text: {@code toString()}. */
        PLAIN(""),
        /** As a short hash of the text: {@code #}. */
        HASH("#"),
        /** Hashed when the parameter name looks sensitive, as text otherwise: {@code #?}. */
        GUESS("#?");

        private final String suffix;

        Hashing(String suffix) {
            this.suffix = suffix;
        }

        /** The suffix of the selector in the configuration. */
        public String suffix() {
            return suffix;
        }
    }

    Hashing hashing();

    record ByName(String name, Hashing hashing) implements ParamRef {

        public ByName(String name) {
            this(name, Hashing.PLAIN);
        }
    }

    record ByIndex(int index, Hashing hashing) implements ParamRef {

        public ByIndex(int index) {
            this(index, Hashing.PLAIN);
        }
    }

    record All(Hashing hashing) implements ParamRef {

        public All() {
            this(Hashing.PLAIN);
        }
    }

    /** {@code "@"}: the method's {@code @StackTraceParam} and {@code @StackTraceParams} annotations. */
    record Annotations(Hashing hashing) implements ParamRef {

        public Annotations() {
            this(Hashing.PLAIN);
        }
    }

    /** {@code "-"}: no parameters, and less specific entries that match the same method are ignored. */
    record Excluded() implements ParamRef {

        @Override
        public Hashing hashing() {
            return Hashing.PLAIN;
        }
    }
}
