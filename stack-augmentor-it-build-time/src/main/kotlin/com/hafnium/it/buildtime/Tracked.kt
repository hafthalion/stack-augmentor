package com.hafnium.it.buildtime

import com.hafnium.stackaugmentor.StackTraceReceiver

/** A default method shows the id of its interface's @StackTraceReceiver member, read from the implementing object. */
interface Tracked {
    @StackTraceReceiver
    fun trackingNumber(): String

    fun track(): Nothing = throw IllegalStateException("cannot track ${trackingNumber()}")
}

/** Kotlin compiles a bridge method into Parcel that calls Tracked.track(): its frame is unchanged. */
class Parcel(@StackTraceReceiver private val code: String) : Tracked {
    override fun trackingNumber() = "T-$code"
}
