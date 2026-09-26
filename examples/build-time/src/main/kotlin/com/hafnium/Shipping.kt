package com.hafnium

import com.hafnium.stackaugmentor.StackTraceParams

/** Every method shows all its parameters; there is no receiver id. */
@StackTraceParams
class Shipping {
    fun ship(orderId: String, quantity: Int): Nothing = throw IllegalStateException("cannot ship")
}
