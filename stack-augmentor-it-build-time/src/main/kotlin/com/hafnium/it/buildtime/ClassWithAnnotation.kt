package com.hafnium.it.buildtime

import com.hafnium.stackaugmentor.AugmentedReceiver
import com.hafnium.stackaugmentor.AugmentedParam
import com.hafnium.stackaugmentor.AugmentedParams

/** An argument is shown with its toString(). */
data class ObjectParam(private val name: String)

/** Receiver id from @AugmentedReceiver, parameter ids from the parameter annotations. */
data class ClassWithAnnotation(
    @AugmentedReceiver
    private val objectId: String
) {
    /** All parameters are shown. */
    @AugmentedParams
    fun transfer(from: String, to: String, amount: Long) {
        method(ObjectParam("object-param-1"), "s3cr3t-token")
    }

    /** The token is shown hashed. */
    fun method(@AugmentedParam param: ObjectParam, @AugmentedParam(secret = true) token: String) {
        error()
    }

    private fun error(): Nothing {
        throw Exception("An error has occurred")
    }
}

/** No annotations: its parameter comes from its own [augment.params] entry. */
data class ClassWithoutAnnotation(
    val objectId: String
) {
    fun method(q: String) {
        ClassWithAnnotation("object-1").transfer("a", "b", 10)
    }
}
