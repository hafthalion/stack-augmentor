package com.hafnium.it.outside

import com.hafnium.it.fixtures.Base
import com.hafnium.stackaugmentor.StackTraceId

// Not in the instrument.annotatedClasses packages: their @StackTraceId annotations are ignored.

class DerivedOutside : Base() {
    fun fail(): Nothing = throw IllegalStateException("outside")
}

class ParamsOnly {
    fun withParam(@StackTraceId code: Int): Nothing = throw IllegalStateException("code $code")
}
