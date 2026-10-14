package com.hafnium.it.fixtures.ignored

import com.hafnium.stackaugmentor.AugmentedReceiver
import com.hafnium.stackaugmentor.AugmentedParam

/** Under the fixtures' "@" patterns, but a "-" class entry comes first: no receiver id, yet its parameter id. */
class Ignored {
    @AugmentedReceiver
    val id = "i-1"

    fun fail(@AugmentedParam count: Int): Nothing = throw IllegalStateException("fail $count")
}
