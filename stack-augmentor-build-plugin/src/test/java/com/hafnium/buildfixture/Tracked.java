package com.hafnium.buildfixture;

import com.hafnium.stackaugmentor.Augmented;
import com.hafnium.stackaugmentor.AugmentedReceiver;

/** Outside the packages that are never instrumented, unlike the test class itself. */
@Augmented
public class Tracked {

    @AugmentedReceiver
    String id = "t-1";

    public void fail() {
    }
}
