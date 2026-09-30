package com.hafnium.examples.buildtime

import com.hafnium.stackaugmentor.StackTraceParams

/** ship shows all its parameters; there is no receiver id. */
class Shipping {
    @StackTraceParams
    fun ship(orderId: String, quantity: Int): Nothing = throw IllegalStateException("cannot ship")
}
