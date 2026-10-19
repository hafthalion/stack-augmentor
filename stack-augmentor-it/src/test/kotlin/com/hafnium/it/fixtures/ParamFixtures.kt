package com.hafnium.it.fixtures

import com.hafnium.stackaugmentor.Augmented
import com.hafnium.stackaugmentor.AugmentedReceiver
import com.hafnium.stackaugmentor.AugmentedParam
import com.hafnium.stackaugmentor.AugmentedParams

// Parameter values selected by @AugmentedParams on methods and classes.

@Augmented
class Accounts {
    @AugmentedParams
    fun transfer(from: String, to: String, amount: Long): Nothing = throw IllegalStateException("transfer")

    @AugmentedParams
    fun move(@AugmentedParam item: String, count: Int): Nothing = throw IllegalStateException("move")

    fun plain(from: String): Nothing = throw IllegalStateException("plain $from")
}

@Augmented
open class Inventory {
    @AugmentedParams
    open fun reserve(sku: String, count: Int): Nothing = throw IllegalStateException("reserve")

    @AugmentedParams
    fun release(sku: String): Nothing = throw IllegalStateException("release")
}

/** The @AugmentedParams of Inventory.reserve does not apply to its override. */
class DerivedInventory : Inventory() {
    override fun reserve(sku: String, count: Int): Nothing = throw IllegalStateException("derived reserve")
}

/** Listed in [augment.params] with x by name and by index: it is shown once. */
@Augmented
class Overlap {
    fun op(@AugmentedParam x: Int, y: Int): Nothing = throw IllegalStateException("op")
}

@Augmented
class Wide {
    @AugmentedParams
    fun ten(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, i: Int, j: Int): Nothing =
        throw IllegalStateException("ten")
}

/**
 * Its annotations are read, but its own entries decide where they match: its receiver members come from its
 * [augment.receiver] entry "getId()", and fail's parameter annotation is ignored ("-"). No [augment.params] entry
 * matches failAnnotated, so its annotation is used.
 */
@Augmented
class Overridden {
    @AugmentedReceiver
    val code = "c-1"

    fun getId() = "o-1"

    fun fail(@AugmentedParam x: Int): Nothing = throw IllegalStateException("fail $x")

    fun failAnnotated(@AugmentedParam y: Int): Nothing = throw IllegalStateException("fail $y")
}

/** Hashed parameter values: secret = true on the annotations. */
@Augmented
class Secrets {
    fun invite(@AugmentedParam user: String, @AugmentedParam(secret = true) email: String): Nothing =
        throw IllegalStateException("invite")

    @AugmentedParams
    fun register(@AugmentedParam(secret = true) email: String, nickname: String): Nothing = throw IllegalStateException("register")
}

/** A hashed parameter that is null. */
@Augmented
class Forgetful {
    fun forget(@AugmentedParam(secret = true) email: String?): Nothing = throw IllegalStateException("forget")
}

// Constructors: parameter values from the "<init>" entry, never a receiver member.

/** Validation in an init block, after the superclass constructor has run. */
@Augmented
class Shipment(@AugmentedParam val orderId: Long, @AugmentedParam(secret = true) val email: String, val weight: Int) {
    init {
        require(weight > 0) { "weight" }
    }
}

open class Parcel(code: String) {
    init {
        require(code.isNotEmpty()) { "code" }
    }
}

/** The superclass constructor throws: inside the super(...) call, so this frame is not augmented. */
@Augmented
class ExpressParcel @AugmentedParams constructor(code: String, val priority: Int) : Parcel(code)

/** Computing the argument of super(...) throws: before the call, so this frame is augmented. */
@Augmented
class Crate @AugmentedParams constructor(code: String?, val size: Int) : Parcel(requireNotNull(code) { "code" })

/** A secondary constructor delegating to the primary one: it is augmented only for exceptions from its own body. */
@Augmented
class Pallet @AugmentedParams constructor(val size: Int) {
    init {
        check(size < 100) { "size" }
    }

    @AugmentedParams
    constructor(label: String, size: Long) : this(size.toInt()) {
        require(label.isNotEmpty()) { "label" }
    }
}
