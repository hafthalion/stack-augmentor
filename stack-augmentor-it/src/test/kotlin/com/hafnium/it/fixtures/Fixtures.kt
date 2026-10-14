package com.hafnium.it.fixtures

import com.hafnium.stackaugmentor.AugmentedReceiver
import com.hafnium.stackaugmentor.AugmentedParam
import com.thirdparty.Order

class ObjectClass {
    @AugmentedReceiver
    val objectId = "object-1"

    fun objectMethod(@AugmentedParam orderId: Int): Nothing = throw Exception("An error has occurred")
}

class KeyedByMethod {
    @AugmentedReceiver
    fun key(): String = "k-1"

    fun fail(): Nothing = throw IllegalStateException("fail")
}

/** Several @AugmentedReceiver members: one id each. */
class MultiKeyed {
    @AugmentedReceiver
    val tenant = "acme"

    @AugmentedReceiver
    val orderId = 42

    fun fail(): Nothing = throw IllegalStateException("fail")
}

open class Base {
    @AugmentedReceiver
    val baseId = "b1"
}

class Derived : Base() {
    fun fail(): Nothing = throw IllegalStateException("fail")
}

/** Annotation on a primary constructor property, without `@field:`. */
class Node(@AugmentedReceiver val name: String, private val next: Node?) {
    fun walk(@AugmentedParam depth: Int) {
        if (next == null) throw IllegalStateException("end of list")
        next.walk(depth + 1)
    }
}

/** Creates an exception and passes it to the next relay, which throws it: the next relay's frame is not in the trace. */
class Relay(@AugmentedReceiver val name: String, private val next: Relay?) {
    fun pass(@AugmentedParam depth: Int, error: IllegalStateException?) {
        if (next == null) throw error!!
        next.pass(depth + 1, IllegalStateException("created by $name"))
    }
}

/** A receiver id with parentheses, which would confuse IDEs looking for "(File.kt:12)". */
class ParenLabels {
    @AugmentedReceiver
    val id = "id(7)"

    fun fail(@AugmentedParam value: Int): Nothing = throw IllegalStateException("fail $value")
}

class ManyParams {
    @AugmentedReceiver
    val id = "many"

    fun six(
        @AugmentedParam a: Int,
        @AugmentedParam b: Long,
        @AugmentedParam c: String,
        @AugmentedParam d: Double,
        @AugmentedParam e: Boolean,
        @AugmentedParam f: Char,
    ): Nothing = throw IllegalStateException("six")

    /** Only fails when asked, to measure the cost of the normal path. */
    fun maybeFail(
        @AugmentedParam a: Int,
        @AugmentedParam b: Int,
        @AugmentedParam c: Int,
        @AugmentedParam d: Int,
        @AugmentedParam e: Int,
        @AugmentedParam f: Int,
        fail: Boolean,
    ): Int {
        if (fail) throw IllegalStateException("asked to fail")
        return a + b + c + d + e + f
    }
}

class Shipping {
    @AugmentedReceiver
    val id = "ship"

    fun ship(@AugmentedParam order: Order?): Nothing = throw IllegalStateException("cannot ship")

    fun op(@AugmentedParam x: Int): Nothing = throw IllegalStateException("op int")

    fun op(@AugmentedParam name: String, y: Int): Nothing = throw IllegalStateException("op string $y")
}

/** Matched by an "@" entry, but without @AugmentedReceiver: not augmented. */
class WithToString {
    override fun toString() = "WTS-1"
    fun fail(): Nothing = throw IllegalStateException("fail")
}

class Plain {
    fun fail(): Nothing = throw IllegalStateException("fail")
}

class BrokenId {
    @AugmentedReceiver
    fun id(): String = throw IllegalStateException("id is broken")

    fun fail(): Nothing = throw IllegalStateException("fail")
}

class MultiLine {
    @AugmentedReceiver
    val text = "line1\nline2"

    fun fail(): Nothing = throw IllegalStateException("fail")
}

class LongId {
    @AugmentedReceiver
    val text = "x".repeat(50)

    fun fail(): Nothing = throw IllegalStateException("fail")
}

class LambdaHolder {
    @AugmentedReceiver
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
    @AugmentedReceiver
    val layer = "layers"

    fun inner(@AugmentedParam step: Int): Nothing = throw IllegalStateException("inner $step")

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

    fun plainInner(@AugmentedParam step: Int): Nothing = throw PlainCause("plain $step")

    fun wrapPlain(): Nothing = try {
        plainInner(4)
    } catch (e: PlainCause) {
        throw RuntimeException("wrapped plain", e)
    }

    /** As [outerWrap], with a cause that is not augmented itself. */
    fun outerWrapPlain(): Nothing = wrapPlain()

    /** [Closer.close] throws too: its exception is suppressed in the one from [inner]. */
    fun closing(): Nothing = Closer().use { inner(3) }

    /** An instrumented frame below [closing]: the exception and its suppressed one share it. */
    fun outerClosing(): Nothing = closing()
}

/** An exception that [augment.exceptions] does not augment. */
class PlainCause(message: String) : RuntimeException(message)

class Closer : AutoCloseable {
    @AugmentedReceiver
    val name = "closer"

    override fun close(): Unit = throw IllegalArgumentException("close failed")
}

fun staticWithParam(@AugmentedParam code: Int): Nothing = throw IllegalStateException("static $code")

fun staticWithoutParam(): Nothing = throw IllegalStateException("static")

class ArrayId {
    @AugmentedReceiver
    val codes = intArrayOf(1, 2)

    fun fail(@AugmentedParam tags: Array<String>): Nothing = throw IllegalStateException("fail ${tags.size}")
}

class NullId {
    @AugmentedReceiver
    val id: String? = null

    fun fail(@AugmentedParam note: String?): Nothing = throw IllegalStateException("fail $note")
}

data class Point(val x: Int)

/** An argument whose toString() throws. */
class Unprintable {
    override fun toString(): String = throw IllegalStateException("cannot print")
}

class Canvas {
    @AugmentedReceiver
    val id = "canvas"

    fun draw(@AugmentedParam point: Point): Nothing = throw IllegalStateException("draw $point")

    fun print(@AugmentedParam item: Unprintable): Nothing = throw IllegalStateException("print")
}

/** Constructors are not instrumented. */
class FailingInit(@AugmentedReceiver val id: String) {
    init {
        check(id.isNotEmpty()) { "empty id" }
    }
}

/** The property of an object is compiled to a static field, which is not an id source. */
object Registry {
    @AugmentedReceiver
    val name = "registry"

    fun fail(@AugmentedParam key: String): Nothing = throw IllegalStateException("no $key")
}

/** Its getter is an instance method, so it is an id source. */
object GetterRegistry {
    @get:AugmentedReceiver
    val name = "getter-registry"

    fun fail(@AugmentedParam key: String): Nothing = throw IllegalStateException("no $key")
}

class Chain {
    @AugmentedReceiver
    val id = "chain"

    fun start(@AugmentedParam n: Int): Nothing = step(n + 1)

    private fun step(@AugmentedParam n: Int): Nothing = throw IllegalStateException("step $n")

    /** Kotlin compiles a static withDefault$default, which fills in b and calls withDefault. */
    fun withDefault(@AugmentedParam a: Int, @AugmentedParam b: Int = 2): Nothing = throw IllegalStateException("default $a $b")

    /** Catches and throws the same exception again. */
    fun rethrow(@AugmentedParam n: Int) {
        try {
            step(n)
        } catch (e: IllegalStateException) {
            throw e
        }
    }
}

/** Throws exceptions created before the call: in the constructor, or by the first call and reused. */
class Prepared {
    @AugmentedReceiver
    val id = "prepared"

    private val created = IllegalStateException("created in the constructor")

    private var shared: IllegalStateException? = null

    fun throwCreated(): Nothing = throw created

    fun throwShared(@AugmentedParam n: Int): Nothing = throw shared ?: IllegalStateException("shared").also { shared = it }

    fun viaShared(@AugmentedParam n: Int): Nothing = throwShared(n)
}

/** Recurses down to depth 0 and throws there, after parking the thread first if asked: ConcurrencyTest. */
class Diver(@AugmentedReceiver val name: String) {
    fun dive(@AugmentedParam depth: Int, @AugmentedParam park: Boolean) {
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
