package com.hafnium

import com.hafnium.stackaugmentor.StackTraceId

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

    fun method(@StackTraceId param: ObjectParam) {
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
