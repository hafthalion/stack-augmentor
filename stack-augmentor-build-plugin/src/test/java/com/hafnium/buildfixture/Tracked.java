package com.hafnium.buildfixture;

import com.hafnium.stackaugmentor.StackTraceReceiver;

/** Outside the packages that are never instrumented, unlike the test class itself. */
public class Tracked {

    @StackTraceReceiver
    String id = "t-1";

    public void fail() {
    }
}
