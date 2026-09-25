package com.hafnium.stackaugmentor.runtime;

/** Where a receiver id comes from, for classes configured externally. */
public sealed interface IdSpec {

    String memberName();

    record FieldSpec(String memberName) implements IdSpec {
    }

    record MethodSpec(String memberName) implements IdSpec {
    }
}
