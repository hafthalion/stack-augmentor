package com.hafnium.stackaugmentor.runtime;

/** The receiver id source an {@code [instrument.classes]} entry names. */
public sealed interface IdSpec {

    record FieldSpec(String memberName) implements IdSpec {
    }

    record MethodSpec(String memberName) implements IdSpec {
    }

    /** {@code "@"}: the class's {@code @StackTraceId}, {@code @StackTraceParam} and {@code @StackTraceParams} annotations. */
    record Annotations() implements IdSpec {
    }

    /**
     * {@code "-"}: the class is ignored: no receiver id, no annotations, and its methods are excluded from
     * {@code [instrument.methods]} entries that are less specific than this entry.
     */
    record Excluded() implements IdSpec {
    }
}
