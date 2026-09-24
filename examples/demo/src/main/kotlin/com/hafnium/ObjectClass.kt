package com.hafnium

import com.hafnium.stackaugmentor.StackTraceId

data class ObjectParam(
    @StackTraceId
    val name: String
)

class ObjectClass(
    @StackTraceId
    val objectId: String
) {
    fun objectMethod(@StackTraceId param: ObjectParam) {
        throw Exception("An error has occured")
    }
}
