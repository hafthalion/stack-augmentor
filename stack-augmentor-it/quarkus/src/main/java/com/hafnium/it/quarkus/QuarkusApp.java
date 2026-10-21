package com.hafnium.it.quarkus;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

/** Prints the stack trace of the exception that {@link Shipping#ship} throws, which the tests check. */
@QuarkusMain
public class QuarkusApp implements QuarkusApplication {

    @Inject
    Shipping shipping;

    @Override
    public int run(String... args) {
        try {
            shipping.ship("o-2");
        } catch (RuntimeException e) {
            e.printStackTrace(System.out);
        }
        return 0;
    }
}
