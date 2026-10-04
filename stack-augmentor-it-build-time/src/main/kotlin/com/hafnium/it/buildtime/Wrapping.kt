package com.hafnium.it.buildtime

import com.hafnium.stackaugmentor.StackTraceId

/** Wraps an exception: the wrapper and its cause share the frame of [outer]. */
class Wrapping(
    @StackTraceId
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
