package com.hafnium.it.fixtures;

import com.hafnium.stackaugmentor.Augmented;
import com.hafnium.stackaugmentor.AugmentedParam;

@Augmented
public class Reassigning {
    public void run(@AugmentedParam String key) {
        key = "new-" + key;
        throw new IllegalStateException(key);
    }
}
