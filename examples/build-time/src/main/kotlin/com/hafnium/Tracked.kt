package com.hafnium

import com.hafnium.stackaugmentor.StackTraceId

/** A default method shows the id of its interface's @StackTraceId member, read from the implementing object. */
interface Tracked {
    @StackTraceId
    fun trackingNumber(): String

    fun track(): Nothing = throw IllegalStateException("cannot track ${trackingNumber()}")
}

/** Kotlin compiles a bridge method into Parcel that calls Tracked.track(): its frame is unchanged. */
class Parcel(@StackTraceId private val code: String) : Tracked {
    override fun trackingNumber() = "T-$code"
}
