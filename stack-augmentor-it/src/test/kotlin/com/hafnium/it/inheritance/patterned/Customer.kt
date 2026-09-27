package com.hafnium.it.inheritance.patterned

/** Matched by the pattern "com.hafnium.it.inheritance.patterned.*" = "code", which also matches Customer$... classes. */
open class Customer(private val code: String) {
    open fun rename(): Nothing = throw IllegalStateException("cannot rename $code")
}
