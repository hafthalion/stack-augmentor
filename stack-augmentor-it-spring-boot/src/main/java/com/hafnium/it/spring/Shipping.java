package com.hafnium.it.spring;

import com.hafnium.stackaugmentor.Augmented;
import com.hafnium.stackaugmentor.AugmentedParam;
import com.hafnium.stackaugmentor.AugmentedReceiver;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
@Augmented
public class Shipping {

    @AugmentedReceiver
    private final String region = "eu";

    public void ship(@AugmentedParam String orderId) {
        Assert.notNull(null, "order " + orderId + " has no address");
    }
}
