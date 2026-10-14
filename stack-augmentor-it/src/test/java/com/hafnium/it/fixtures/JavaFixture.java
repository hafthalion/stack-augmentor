package com.hafnium.it.fixtures;

import com.hafnium.stackaugmentor.StackTraceReceiver;
import com.hafnium.stackaugmentor.StackTraceParam;

/** Compiled without -parameters, so parameter names are not in the class file. */
public class JavaFixture {

    @StackTraceReceiver
    private final String key = "java-1";

    public void run(@StackTraceParam int value) {
        throw new IllegalStateException("value " + value);
    }
}
