package com.hafnium.it.fixtures

import com.hafnium.stackaugmentor.StackTraceId
import com.thirdparty.Order

class ObjectClass {
    @StackTraceId
    val objectId = "object-1"

    fun objectMethod(@StackTraceId orderId: Int): Nothing = throw Exception("An error has occured")
}

class KeyedByMethod {
    @StackTraceId
    fun key(): String = "k-1"

    fun fail(): Nothing = throw IllegalStateException("fail")
}

class Renamed {
    @StackTraceId(name = "user")
    val login = "bob"

    fun fail(): Nothing = throw IllegalStateException("fail")
}

open class Base {
    @StackTraceId
    val baseId = "b1"
}

class Derived : Base() {
    fun fail(): Nothing = throw IllegalStateException("fail")
}

/** Annotation on a primary constructor property, without `@field:`. */
class Node(@StackTraceId val name: String, private val next: Node?) {
    fun walk(@StackTraceId depth: Int) {
        if (next == null) throw IllegalStateException("end of list")
        next.walk(depth + 1)
    }
}

class ManyParams {
    @StackTraceId
    val id = "many"

    fun six(
        @StackTraceId a: Int,
        @StackTraceId b: Long,
        @StackTraceId c: String,
        @StackTraceId d: Double,
        @StackTraceId e: Boolean,
        @StackTraceId f: Char,
    ): Nothing = throw IllegalStateException("six")

    /** Only fails when asked, to measure the cost of the normal path. */
    fun maybeFail(
        @StackTraceId a: Int,
        @StackTraceId b: Int,
        @StackTraceId c: Int,
        @StackTraceId d: Int,
        @StackTraceId e: Int,
        @StackTraceId f: Int,
        fail: Boolean,
    ): Int {
        if (fail) throw IllegalStateException("asked to fail")
        return a + b + c + d + e + f
    }
}

class Shipping {
    @StackTraceId
    val id = "ship"

    fun ship(@StackTraceId order: Order?): Nothing = throw IllegalStateException("cannot ship")

    fun op(@StackTraceId x: Int): Nothing = throw IllegalStateException("op int")

    fun op(@StackTraceId name: String, y: Int): Nothing = throw IllegalStateException("op string $y")
}

/** In an instrument.annotatedClasses package, but without @StackTraceId: not augmented. */
class WithToString {
    override fun toString() = "WTS-1"
    fun fail(): Nothing = throw IllegalStateException("fail")
}

class Plain {
    fun fail(): Nothing = throw IllegalStateException("fail")
}

class BrokenId {
    @StackTraceId
    fun id(): String = throw IllegalStateException("id is broken")

    fun fail(): Nothing = throw IllegalStateException("fail")
}

class MultiLine {
    @StackTraceId
    val text = "line1\nline2"

    fun fail(): Nothing = throw IllegalStateException("fail")
}

class LongId {
    @StackTraceId
    val text = "x".repeat(50)

    fun fail(): Nothing = throw IllegalStateException("fail")
}

class LambdaHolder {
    @StackTraceId
    val id = "lambda"

    fun viaLambda() {
        val action = Runnable { throw IllegalStateException("from lambda") }
        action.run()
    }
}

class NoTraceException : RuntimeException("no trace", null, false, false)

class NoTrace {
    fun fail(): Nothing = throw NoTraceException()
}

class Layers {
    @StackTraceId
    val layer = "layers"

    fun inner(@StackTraceId step: Int): Nothing = throw IllegalStateException("inner $step")

    /** Catches the exception and hands it back, like code that logs and carries on. */
    fun catchAndReturn(): Throwable = try {
        inner(1)
    } catch (e: IllegalStateException) {
        e
    }

    fun wrap(): Nothing = try {
        inner(2)
    } catch (e: IllegalStateException) {
        throw RuntimeException("wrapped", e)
    }
}

fun staticWithParam(@StackTraceId code: Int): Nothing = throw IllegalStateException("static $code")

fun staticWithoutParam(): Nothing = throw IllegalStateException("static")
