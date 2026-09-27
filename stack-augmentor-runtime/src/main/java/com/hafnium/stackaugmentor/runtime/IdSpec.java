package com.hafnium.stackaugmentor.runtime;

/** The receiver id source an {@code [augment.classes]} entry names. */
public sealed interface IdSpec {

    record FieldSpec(String memberName) implements IdSpec {
    }

    record MethodSpec(String memberName) implements IdSpec {
    }

    /** {@code "@"}: the {@code @StackTraceId} of the class. The parameter annotations are {@code [augment.methods]}'s. */
    record Annotations() implements IdSpec {
    }

    /** {@code "-"}: no receiver id, even if a less specific entry would give one. Parameter ids are not affected. */
    record Excluded() implements IdSpec {
    }
}
