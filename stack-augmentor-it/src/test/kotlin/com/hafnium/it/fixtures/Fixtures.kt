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

/** Several @StackTraceId members: one id each. */
class MultiKeyed {
    @StackTraceId
    val tenant = "acme"

    @StackTraceId
    val orderId = 42

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

/** A receiver id with parentheses, which would confuse IDEs looking for "(File.kt:12)". */
class ParenLabels {
    @StackTraceId
    val id = "id(7)"

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

    /** An instrumented frame below the catch in [wrap]: the wrapper and its cause share it. */
    fun outerWrap(): Nothing = wrap()

    /** [Closer.close] throws too: its exception is suppressed in the one from [inner]. */
    fun closing(): Nothing = Closer().use { inner(3) }

    /** An instrumented frame below [closing]: the exception and its suppressed one share it. */
    fun outerClosing(): Nothing = closing()
}

class Closer : AutoCloseable {
    @StackTraceId
    val name = "closer"

    override fun close(): Unit = throw IllegalArgumentException("close failed")
}

fun staticWithParam(@StackTraceParam code: Int): Nothing = throw IllegalStateException("static $code")

fun staticWithoutParam(): Nothing = throw IllegalStateException("static")

class ArrayId {
    @StackTraceId
    val codes = intArrayOf(1, 2)

    fun fail(@StackTraceParam tags: Array<String>): Nothing = throw IllegalStateException("fail ${tags.size}")
}

class NullId {
    @StackTraceId
    val id: String? = null

    fun fail(@StackTraceParam note: String?): Nothing = throw IllegalStateException("fail $note")
}

data class Point(val x: Int)

/** An argument whose toString() throws. */
class Unprintable {
    override fun toString(): String = throw IllegalStateException("cannot print")
}

class Canvas {
    @StackTraceId
    val id = "canvas"

    fun draw(@StackTraceParam point: Point): Nothing = throw IllegalStateException("draw $point")

    fun print(@StackTraceParam item: Unprintable): Nothing = throw IllegalStateException("print")
}

/** Constructors are not instrumented. */
class FailingInit(@StackTraceId val id: String) {
    init {
        check(id.isNotEmpty()) { "empty id" }
    }
}

/** The property of an object is compiled to a static field, which is not an id source. */
object Registry {
    @StackTraceId
    val name = "registry"

    fun fail(@StackTraceParam key: String): Nothing = throw IllegalStateException("no $key")
}

/** Its getter is an instance method, so it is an id source. */
object GetterRegistry {
    @get:StackTraceId
    val name = "getter-registry"

    fun fail(@StackTraceParam key: String): Nothing = throw IllegalStateException("no $key")
}

class Chain {
    @StackTraceId
    val id = "chain"

    fun start(@StackTraceParam n: Int): Nothing = step(n + 1)

    private fun step(@StackTraceParam n: Int): Nothing = throw IllegalStateException("step $n")

    /** Kotlin compiles a static withDefault$default, which fills in b and calls withDefault. */
    fun withDefault(@StackTraceParam a: Int, @StackTraceParam b: Int = 2): Nothing = throw IllegalStateException("default $a $b")

    /** Catches and throws the same exception again. */
    fun rethrow(@StackTraceParam n: Int) {
        try {
            step(n)
        } catch (e: IllegalStateException) {
            throw e
        }
    }
}

/** Throws exceptions created before the call: in the constructor, or by the first call and reused. */
class Prepared {
    @StackTraceId
    val id = "prepared"

    private val created = IllegalStateException("created in the constructor")

    private var shared: IllegalStateException? = null

    fun throwCreated(): Nothing = throw created

    fun throwShared(@StackTraceParam n: Int): Nothing = throw shared ?: IllegalStateException("shared").also { shared = it }

    fun viaShared(@StackTraceParam n: Int): Nothing = throwShared(n)
}

/** Recurses down to depth 0 and throws there, after parking the thread first if asked: ConcurrencyTest. */
class Diver(@StackTraceId val name: String) {
    fun dive(@StackTraceParam depth: Int, @StackTraceParam park: Boolean) {
        if (depth == 0) {
            if (park) {
                // A virtual thread unmounts here and may continue on another carrier thread.
                Thread.sleep(1)
            }
            throw IllegalStateException("bottom of $name")
        }
        dive(depth - 1, park)
    }
}
