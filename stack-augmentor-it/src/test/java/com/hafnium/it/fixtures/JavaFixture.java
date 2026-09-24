package com.hafnium.it.fixtures;

import com.hafnium.stackaugmentor.StackTraceId;

/** Compiled without -parameters, so parameter names are not in the class file. */
public class JavaFixture {

    @StackTraceId
    private final String key = "java-1";

    public void run(@StackTraceId int value) {
        throw new IllegalStateException("value " + value);
    }

    public void named(@StackTraceId(name = "count") int value) {
        throw new IllegalStateException("value " + value);
    }
}
