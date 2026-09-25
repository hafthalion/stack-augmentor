package com.hafnium

import com.hafnium.stackaugmentor.StackTraceId

data class ObjectParam(
    @StackTraceId
    val name: String
)

data class ObjectWithAnnotation(
    @StackTraceId
    private val objectId: String
) {
    private fun error(): Nothing {
        throw Exception("An error has occured")
    }

    fun objectMethod(@StackTraceId param: ObjectParam) {
        error()
    }
}

data class ObjectWithoutAnnotation(
    val objectId: String
) {
    fun objectMethod() {
        throw Exception("An error has occured")
    }
}
