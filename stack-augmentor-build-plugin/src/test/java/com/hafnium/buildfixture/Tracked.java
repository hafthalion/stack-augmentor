package com.hafnium.buildfixture;

import com.hafnium.stackaugmentor.StackTraceId;

/** Outside the packages that are never instrumented, unlike the test class itself. */
public class Tracked {

    @StackTraceId
    String id = "t-1";

    public void fail() {
    }
}
