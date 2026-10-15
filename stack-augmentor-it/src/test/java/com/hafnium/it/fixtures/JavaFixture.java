package com.hafnium.it.fixtures;

import com.hafnium.stackaugmentor.Augmented;
import com.hafnium.stackaugmentor.AugmentedReceiver;
import com.hafnium.stackaugmentor.AugmentedParam;

/** Compiled without -parameters, so parameter names are not in the class file. */
@Augmented
public class JavaFixture {

    @AugmentedReceiver
    private final String key = "java-1";

    public void run(@AugmentedParam int value) {
        throw new IllegalStateException("value " + value);
    }
}
