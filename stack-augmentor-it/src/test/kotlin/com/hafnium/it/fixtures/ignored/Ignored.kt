package com.hafnium.it.fixtures.ignored

import com.hafnium.stackaugmentor.StackTraceId
import com.hafnium.stackaugmentor.StackTraceParam

/** Under the fixtures' "@" patterns, but a more specific "-" class entry: no receiver id, yet its parameter id. */
class Ignored {
    @StackTraceId
    val id = "i-1"

    fun fail(@StackTraceParam count: Int): Nothing = throw IllegalStateException("fail $count")
}
