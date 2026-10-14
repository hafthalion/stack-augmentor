package com.hafnium.examples.buildtime

import com.hafnium.stackaugmentor.StackTraceReceiver
import com.hafnium.stackaugmentor.StackTraceParam
import com.hafnium.stackaugmentor.StackTraceParams

/** An argument is shown with its toString(). */
data class ObjectParam(private val name: String)

/** Receiver id from @StackTraceReceiver, parameter ids from the parameter annotations. */
data class ClassWithAnnotation(
    @StackTraceReceiver
    private val objectId: String
) {
    /** All parameters are shown. */
    @StackTraceParams
    fun transfer(from: String, to: String, amount: Long) {
        method(ObjectParam("object-param-1"), "s3cr3t-token")
    }

    /** The token is shown hashed. */
    fun method(@StackTraceParam param: ObjectParam, @StackTraceParam(secret = true) token: String) {
        error()
    }

    private fun error(): Nothing {
        throw Exception("An error has occurred")
    }
}

/** No annotations: its parameter comes from its own [augment.params] entry, which comes before the "@" pattern. */
data class ClassWithoutAnnotation(
    val objectId: String
) {
    fun method(q: String) {
        ClassWithAnnotation("object-1").transfer("a", "b", 10)
    }
}
