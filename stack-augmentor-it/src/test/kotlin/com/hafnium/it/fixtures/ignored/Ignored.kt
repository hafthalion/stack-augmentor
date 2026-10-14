package com.hafnium.it.fixtures.ignored

import com.hafnium.stackaugmentor.StackTraceReceiver
import com.hafnium.stackaugmentor.StackTraceParam

/** Under the fixtures' "@" patterns, but a "-" class entry comes first: no receiver id, yet its parameter id. */
class Ignored {
    @StackTraceReceiver
    val id = "i-1"

    fun fail(@StackTraceParam count: Int): Nothing = throw IllegalStateException("fail $count")
}
