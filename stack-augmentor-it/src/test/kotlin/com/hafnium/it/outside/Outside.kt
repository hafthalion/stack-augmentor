package com.hafnium.it.outside

import com.hafnium.it.fixtures.Base
import com.hafnium.stackaugmentor.StackTraceParam
import com.hafnium.stackaugmentor.StackTraceParams
import com.thirdparty.SavingsAccount

// No [augment.receiver] entry matches these classes, and entries of their superclasses do not apply to them: their
// @StackTraceId is ignored. Their parameter annotations are ignored, unless an [augment.params] "@" entry applies.

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

/** Its parameter annotations are enabled by an [augment.params] "@" entry. */
class MethodAnnotationsOutside {
    fun run(@StackTraceParam code: Int): Nothing = throw IllegalStateException("run $code")
}

/** Its class-level annotation is enabled by an [augment.params] "@" entry for all its methods. */
@StackTraceParams
class ClassParamsViaMethods {
    fun run(a: Int, b: String): Nothing = throw IllegalStateException("run $a $b")
}

/** Its superclass SavingsAccount has an [augment.receiver] entry, which does not apply to it. */
class PremiumAccount(number: String) : SavingsAccount(number) {
    fun upgrade(): Nothing = throw IllegalStateException("upgrade")
}
