package com.hafnium.buildfixture;

import com.hafnium.stackaugmentor.Augmented;
import com.hafnium.stackaugmentor.AugmentedReceiver;

/** ByteBuddyPluginTest applies the plugin to it and then calls it. */
@Augmented
public class Failing {

    @AugmentedReceiver
    String id = "f-1";

    public void fail() {
        throw new IllegalStateException("fail");
    }
}
