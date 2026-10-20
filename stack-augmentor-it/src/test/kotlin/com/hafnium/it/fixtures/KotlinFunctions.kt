package com.hafnium.it.fixtures

import com.hafnium.stackaugmentor.Augmented
import com.hafnium.stackaugmentor.AugmentedParam
import com.hafnium.stackaugmentor.AugmentedReceiver
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

// Kotlin functions whose JVM form differs from their source: suspend, inline, default arguments.

@Augmented
class Coroutines(@AugmentedReceiver val id: String) {
    /** Where [load] waits; the caller resumes it. */
    var waiting: Continuation<Unit>? = null

    suspend fun failNow(@AugmentedParam key: String): Nothing = throw IllegalStateException("now $key")

    suspend fun load(@AugmentedParam key: String): Nothing {
        suspendCoroutine { waiting = it }
        throw IllegalStateException("after suspension $key")
    }

    fun withDefault(@AugmentedParam key: String, @AugmentedParam count: Int = 3): Nothing = throw IllegalStateException("default $key $count")

    @Suppress("NOTHING_TO_INLINE")
    inline fun inlined(@AugmentedParam key: String): Nothing = throw IllegalStateException("inlined $key")

    fun callsInlined(@AugmentedParam outer: String): Nothing = inlined("in-$outer")
}
