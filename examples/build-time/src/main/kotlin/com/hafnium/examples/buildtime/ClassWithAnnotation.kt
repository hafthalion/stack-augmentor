package com.hafnium.examples.buildtime

import com.hafnium.stackaugmentor.StackTraceId
import com.hafnium.stackaugmentor.StackTraceParam

/** An argument is shown with its toString(). */
data class ObjectParam(private val name: String)

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
}

data class ClassWithoutAnnotation(
    val objectId: String
) {
    fun method() {
        throw Exception("An error has occurred")
    }
}
