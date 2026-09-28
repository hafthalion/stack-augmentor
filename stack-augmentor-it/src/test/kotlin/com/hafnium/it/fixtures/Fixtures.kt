package com.hafnium.it.fixtures

import com.hafnium.stackaugmentor.StackTraceId
import com.hafnium.stackaugmentor.StackTraceParam
import com.thirdparty.Order

class ObjectClass {
    @StackTraceId
    val objectId = "object-1"

    fun objectMethod(@StackTraceParam orderId: Int): Nothing = throw Exception("An error has occurred")
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
    fun walk(@StackTraceParam depth: Int) {
        if (next == null) throw IllegalStateException("end of list")
        next.walk(depth + 1)
    }
}

/** Creates an exception and passes it to the next relay, which throws it: the next relay's frame is not in the trace. */
class Relay(@StackTraceId val name: String, private val next: Relay?) {
    fun pass(@StackTraceParam depth: Int, error: IllegalStateException?) {
        if (next == null) throw error!!
        next.pass(depth + 1, IllegalStateException("created by $name"))
    }
}

/** A receiver label with parentheses, which would confuse IDEs looking for "(File.kt:12)". */
class ParenLabels {
    @StackTraceId(name = "id(x)")
    val id = "7"

    fun fail(@StackTraceParam value: Int): Nothing = throw IllegalStateException("fail $value")
}

class ManyParams {
    @StackTraceId
    val id = "many"

    fun six(
        @StackTraceParam a: Int,
        @StackTraceParam b: Long,
        @StackTraceParam c: String,
        @StackTraceParam d: Double,
        @StackTraceParam e: Boolean,
        @StackTraceParam f: Char,
    ): Nothing = throw IllegalStateException("six")

    /** Only fails when asked, to measure the cost of the normal path. */
    fun maybeFail(
        @StackTraceParam a: Int,
        @StackTraceParam b: Int,
        @StackTraceParam c: Int,
        @StackTraceParam d: Int,
        @StackTraceParam e: Int,
        @StackTraceParam f: Int,
        fail: Boolean,
    ): Int {
        if (fail) throw IllegalStateException("asked to fail")
        return a + b + c + d + e + f
    }
}

class Shipping {
    @StackTraceId
    val id = "ship"

    fun ship(@StackTraceParam order: Order?): Nothing = throw IllegalStateException("cannot ship")

    fun op(@StackTraceParam x: Int): Nothing = throw IllegalStateException("op int")

    fun op(@StackTraceParam name: String, y: Int): Nothing = throw IllegalStateException("op string $y")
}

/** Matched by an "@" entry, but without @StackTraceId: not augmented. */
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

    fun inner(@StackTraceParam step: Int): Nothing = throw IllegalStateException("inner $step")

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

fun staticWithParam(@StackTraceParam code: Int): Nothing = throw IllegalStateException("static $code")

fun staticWithoutParam(): Nothing = throw IllegalStateException("static")
