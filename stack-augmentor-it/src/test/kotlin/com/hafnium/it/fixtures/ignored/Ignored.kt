package com.hafnium.it.fixtures.ignored

import com.hafnium.stackaugmentor.StackTraceId
import com.hafnium.stackaugmentor.StackTraceParam

/** Annotated, and under the fixtures' "@" pattern, but a more specific "-" entry ignores the class. */
class Ignored {
    @StackTraceId
    val id = "i-1"

    fun fail(@StackTraceParam count: Int): Nothing = throw IllegalStateException("fail $count")
}
