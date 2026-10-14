package com.hafnium.it.buildtime

import com.hafnium.stackaugmentor.StackTraceReceiver

/** Wraps an exception: the wrapper and its cause share the frame of [outer]. */
class Wrapping(
    @StackTraceReceiver
    private val name: String
) {
    fun outer(): Nothing = wrap()

    private fun wrap(): Nothing = try {
        inner()
    } catch (e: IllegalStateException) {
        throw RuntimeException("wrapped", e)
    }

    private fun inner(): Nothing = throw IllegalStateException("inner")
}
