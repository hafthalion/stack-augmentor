package com.hafnium

import com.hafnium.stackaugmentor.StackTraceId
import com.hafnium.stackaugmentor.StackTraceParam
import com.hafnium.stackaugmentor.StackTraceParams

data class ObjectParam(
    @StackTraceId
    val name: String
)

data class ClassWithAnnotation(
    @StackTraceId
    private val objectId: String
) {
    private fun error(): Nothing {
        throw Exception("An error has occurred")
    }

    fun method(@StackTraceParam param: ObjectParam) {
        error()
    }

    /** All parameters are shown. */
    @StackTraceParams
    fun transfer(from: String, to: String, amount: Long) {
        error()
    }
}

data class ClassWithoutAnnotation(
    val objectId: String
) {
    fun method(q: String) {
        throw Exception("An error has occurred")
    }
}
