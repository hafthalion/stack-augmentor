package com.hafnium.it.fixtures

import com.hafnium.stackaugmentor.StackTraceId
import com.hafnium.stackaugmentor.StackTraceParam
import com.hafnium.stackaugmentor.StackTraceParams

// Parameter ids selected by @StackTraceParams on methods and classes.

class Accounts {
    @StackTraceParams
    fun transfer(from: String, to: String, amount: Long): Nothing = throw IllegalStateException("transfer")

    @StackTraceParams
    fun move(@StackTraceParam item: String, count: Int): Nothing = throw IllegalStateException("move")

    fun plain(from: String): Nothing = throw IllegalStateException("plain $from")
}

open class Inventory {
    @StackTraceParams
    open fun reserve(sku: String, count: Int): Nothing = throw IllegalStateException("reserve")

    @StackTraceParams
    fun release(sku: String): Nothing = throw IllegalStateException("release")
}

/** The @StackTraceParams of Inventory.reserve does not apply to its override. */
class DerivedInventory : Inventory() {
    override fun reserve(sku: String, count: Int): Nothing = throw IllegalStateException("derived reserve")
}

/** Listed in [augment.params] with x by name and by index: it is shown once. */
class Overlap {
    fun op(@StackTraceParam x: Int, y: Int): Nothing = throw IllegalStateException("op")
}

class Wide {
    @StackTraceParams
    fun ten(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, i: Int, j: Int): Nothing =
        throw IllegalStateException("ten")
}

/**
 * Matched by the "@" entries of the fixtures, but more specific entries decide: its receiver id comes from
 * "getId()", and its parameter annotations are ignored ("-"), except those of failAnnotated, whose exact
 * [augment.params] "@" entry is more specific still.
 */
class Overridden {
    @StackTraceId
    val code = "c-1"

    fun getId() = "o-1"

    fun fail(@StackTraceParam x: Int): Nothing = throw IllegalStateException("fail $x")

    fun failAnnotated(@StackTraceParam y: Int): Nothing = throw IllegalStateException("fail $y")
}

/** Hashed parameter ids: secret = true on the annotations. */
class Secrets {
    fun invite(@StackTraceParam user: String, @StackTraceParam(secret = true) email: String): Nothing =
        throw IllegalStateException("invite")

    @StackTraceParams
    fun register(@StackTraceParam(secret = true) email: String, nickname: String): Nothing = throw IllegalStateException("register")
}

/** A hashed parameter that is null. */
class Forgetful {
    fun forget(@StackTraceParam(secret = true) email: String?): Nothing = throw IllegalStateException("forget")
}

// Constructors: parameter ids from the "<init>" entry, never a receiver id.

/** Validation in an init block, after the superclass constructor has run. */
class Shipment(@StackTraceParam val orderId: Long, @StackTraceParam(secret = true) val email: String, val weight: Int) {
    init {
        require(weight > 0) { "weight" }
    }
}

open class Parcel(code: String) {
    init {
        require(code.isNotEmpty()) { "code" }
    }
}

/** The superclass constructor throws: inside the super(...) call, so this frame gets no ids. */
class ExpressParcel @StackTraceParams constructor(code: String, val priority: Int) : Parcel(code)

/** Computing the argument of super(...) throws: before the call, so this frame gets its ids. */
class Crate @StackTraceParams constructor(code: String?, val size: Int) : Parcel(requireNotNull(code) { "code" })

/** A secondary constructor delegating to the primary one: it gets ids only for exceptions from its own body. */
class Pallet @StackTraceParams constructor(val size: Int) {
    init {
        check(size < 100) { "size" }
    }

    @StackTraceParams
    constructor(label: String, size: Long) : this(size.toInt()) {
        require(label.isNotEmpty()) { "label" }
    }
}
