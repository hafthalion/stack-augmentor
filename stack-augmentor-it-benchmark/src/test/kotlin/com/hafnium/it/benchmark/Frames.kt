package com.hafnium.it.benchmark

import com.hafnium.stackaugmentor.StackTraceId
import com.hafnium.stackaugmentor.StackTraceParam

/** Thrown at the bottom of the stack, or wrapping the one from below: then [wraps] counts the wrappings, from 1. */
class BenchmarkException private constructor(cause: BenchmarkException?, val wraps: Int) : RuntimeException(
    if (cause == null) "thrown at the bottom of the stack" else "wrap $wraps: wrapping the exception from below",
    cause,
) {
    constructor() : this(null, 0)

    constructor(cause: BenchmarkException) : this(cause, cause.wraps + 1)
}

/** A frame that shows ids: the receiver's name and the depth. */
class Configured(@StackTraceId private val name: String) {
    fun step(@StackTraceParam depth: Int, stack: Stack): Int = stack.next(depth)
}

/** A frame that shows nothing. */
class Plain {
    fun step(depth: Int, stack: Stack): Int = stack.next(depth)
}

/**
 * A stack of [Scenario.frames] frames, each a `step` of [Configured] or [Plain], with depths from 1 at the top: the
 * exception is created in the deepest frame, at depth [Scenario.bottom], wrapped in new ones every [Scenario.wrapEvery] frames if the scenario has causes, and
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
    fun run(): Int = call(1)

    inline fun call(depth: Int): Int =
        // Counted from the bottom, so that the deepest frame, which creates the exception, is configured.
        if (scenario.configuredEvery > 0 && (scenario.bottom - depth) % scenario.configuredEvery == 0) configured.step(depth, this) else plain.step(depth, this)

    inline fun next(depth: Int): Int {
        if (depth == scenario.bottom) {
            throw BenchmarkException()
        }
        if (scenario.wrapsAt(depth)) {
            return try {
                call(depth + 1) + 1
            } catch (e: BenchmarkException) {
                throw BenchmarkException(e)
            }
        }
        if (depth != catchAt) {
            return call(depth + 1) + 1
        }
        return try {
            call(depth + 1) + 1
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
