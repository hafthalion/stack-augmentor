package com.hafnium.it.outside

import com.hafnium.it.fixtures.Base
import com.hafnium.stackaugmentor.StackTraceId

/** Not in the include packages: instrumented only because it inherits an annotated id. */
class DerivedOutside : Base() {
    fun fail(): Nothing = throw IllegalStateException("outside")
}

/** Not in the include packages and no receiver id: only the method with an id parameter is instrumented. */
class ParamsOnly {
    fun withParam(@StackTraceId code: Int): Nothing = throw IllegalStateException("code $code")
    fun withoutParam(): Nothing = throw IllegalStateException("plain")
}
