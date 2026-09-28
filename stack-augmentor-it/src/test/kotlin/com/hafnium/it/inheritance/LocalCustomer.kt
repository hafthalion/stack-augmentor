package com.hafnium.it.inheritance

import com.hafnium.it.inheritance.patterned.Customer

/** A subclass of Customer outside the package that the pattern "com.hafnium.it.inheritance.patterned.*" matches. */
class LocalCustomer(code: String) : Customer(code) {
    fun relocate(): Nothing = throw IllegalStateException("cannot relocate")
}
