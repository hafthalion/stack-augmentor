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

/** Also listed in [augment.params] with both parameters: each is shown once. */
class Overlap {
    fun op(@StackTraceParam x: Int, y: Int): Nothing = throw IllegalStateException("op")
}

/** Counts toString() calls, to check that parameters beyond maxParams are not resolved. */
class Counted {
    override fun toString(): String {
        calls++
        throw IllegalStateException("not to be called")
    }

    companion object {
        @JvmStatic
        var calls = 0
    }
}

class Wide {
    @StackTraceParams
    fun ten(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, i: Int, j: Counted): Nothing =
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
