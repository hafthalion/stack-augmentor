package com.hafnium.it.quarkus;

import com.hafnium.stackaugmentor.Augmented;
import com.hafnium.stackaugmentor.AugmentedParam;
import com.hafnium.stackaugmentor.AugmentedReceiver;
import io.smallrye.mutiny.helpers.ParameterValidation;
import jakarta.enterprise.context.ApplicationScoped;

/** Application scoped: callers reach it through the client proxy that Quarkus generates. */
@ApplicationScoped
@Augmented
public class Shipping {

    @AugmentedReceiver
    String region = "us";

    public void ship(@AugmentedParam String orderId) {
        ParameterValidation.nonNull(null, "address of " + orderId);
    }
}
