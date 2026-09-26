package com.hafnium.it.outside

import com.hafnium.it.fixtures.Base
import com.hafnium.stackaugmentor.StackTraceParam
import com.hafnium.stackaugmentor.StackTraceParams

// Not in the instrument.annotatedClasses packages: their annotations are ignored.

class DerivedOutside : Base() {
    fun fail(): Nothing = throw IllegalStateException("outside")
}

class ParamsOnly {
    fun withParam(@StackTraceParam code: Int): Nothing = throw IllegalStateException("code $code")
}

@StackTraceParams
class AllParamsOutside {
    fun run(x: Int): Nothing = throw IllegalStateException("run $x")
}
