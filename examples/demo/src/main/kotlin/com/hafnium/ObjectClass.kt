package com.hafnium

import com.hafnium.stackaugmentor.StackTraceId

class ObjectClass(
    @StackTraceId
    val objectId: String
) {
    fun objectMethod(@StackTraceId orderId: Int) {
        throw Exception("An error has occured")
    }
}
