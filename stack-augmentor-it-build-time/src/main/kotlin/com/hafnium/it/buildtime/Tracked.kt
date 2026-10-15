package com.hafnium.it.buildtime

import com.hafnium.stackaugmentor.Augmented
import com.hafnium.stackaugmentor.AugmentedReceiver

/** A default method shows the id of its interface's @AugmentedReceiver member, read from the implementing object. */
@Augmented
interface Tracked {
    @AugmentedReceiver
    fun trackingNumber(): String

    fun track(): Nothing = throw IllegalStateException("cannot track ${trackingNumber()}")
}

/** Kotlin compiles a bridge method into Parcel that calls Tracked.track(): its frame is unchanged. */
@Augmented
class Parcel(@AugmentedReceiver private val code: String) : Tracked {
    override fun trackingNumber() = "T-$code"
}
