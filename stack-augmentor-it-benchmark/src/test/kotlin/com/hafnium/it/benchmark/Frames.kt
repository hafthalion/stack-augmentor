package com.hafnium.it.benchmark

import com.hafnium.stackaugmentor.StackTraceId
import com.hafnium.stackaugmentor.StackTraceParam

class BenchmarkException(cause: BenchmarkException? = null) :
    RuntimeException(if (cause == null) "thrown at the bottom of the stack" else "wrapping the exception from below", cause)

/** A frame that shows ids: the receiver's name and the depth. */
class Configured(@StackTraceId private val name: String) {
    fun step(@StackTraceParam depth: Int, stack: Stack): Int = stack.next(depth)
}

/** A frame that shows nothing. */
class Plain {
    fun step(depth: Int, stack: Stack): Int = stack.next(depth)
}

/**
 * A stack of [Scenario.frames] frames, each a `step` of [Configured] or [Plain]: the exception is created in the frame
 * at depth 0, the deepest one, wrapped in new ones every [Scenario.wrapEvery] frames if the scenario has causes, and
 * caught in the frame at depth [catchAt]. The functions that link the frames are
 * inlined, so every frame of the stack is a `step`.
 */
class Stack(val scenario: Scenario, private val logged: Boolean) {

    val configured = Configured("service")
    val plain = Plain()
    val catchAt = scenario.catchAt

    /** What the last caught exception was logged as; only kept to use it. */
    var lastLog: String = ""
        private set
    private var unlogged = 0

    /** Runs the stack once: one exception created, caught and logged or not. */
    fun run(): Int = call(scenario.frames - 1)

    inline fun call(depth: Int): Int =
        if (scenario.configuredEvery > 0 && depth % scenario.configuredEvery == 0) configured.step(depth, this) else plain.step(depth, this)

    inline fun next(depth: Int): Int {
        if (depth == 0) {
            throw BenchmarkException()
        }
        if (scenario.wrapEvery > 0 && depth % scenario.wrapEvery == 0 && depth <= scenario.outermostCreatedAt) {
            return try {
                call(depth - 1) + 1
            } catch (e: BenchmarkException) {
                throw BenchmarkException(e)
            }
        }
        if (depth != catchAt) {
            return call(depth - 1) + 1
        }
        return try {
            call(depth - 1) + 1
        } catch (e: BenchmarkException) {
            caught(e)
            0
        }
    }

    fun caught(e: BenchmarkException) {
        if (logged) {
            // What a logger does with it.
            lastLog = e.stackTraceToString()
        } else {
            unlogged += System.identityHashCode(e) and 1
        }
    }
}
