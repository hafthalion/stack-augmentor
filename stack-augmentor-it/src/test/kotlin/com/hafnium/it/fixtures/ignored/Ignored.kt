package com.hafnium.it.fixtures.ignored

import com.hafnium.stackaugmentor.AugmentedReceiver
import com.hafnium.stackaugmentor.AugmentedParam

/** Its annotations are read, but a "-" receiver entry matches it: no receiver members, yet its parameter value. */
class Ignored {
    @AugmentedReceiver
    val id = "i-1"

    fun fail(@AugmentedParam count: Int): Nothing = throw IllegalStateException("fail $count")
}
