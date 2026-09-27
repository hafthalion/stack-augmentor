package com.hafnium.it.outside

import com.hafnium.it.fixtures.Base
import com.hafnium.stackaugmentor.StackTraceParam
import com.hafnium.stackaugmentor.StackTraceParams
import com.thirdparty.SavingsAccount

// No entry matches these classes: their annotations are ignored, unless an [augment.classes] entry of a
// superclass applies (for @StackTraceId), or an [augment.methods] "@" entry (for the parameter annotations).

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

/** Its parameter annotations are enabled by an [augment.methods] "@" entry. */
class MethodAnnotationsOutside {
    fun run(@StackTraceParam code: Int): Nothing = throw IllegalStateException("run $code")
}

/** Its class-level annotation is enabled by an [augment.methods] "@" entry for all its methods. */
@StackTraceParams
class ClassParamsViaMethods {
    fun run(a: Int, b: String): Nothing = throw IllegalStateException("run $a $b")
}

/** Gets the receiver id of the [augment.classes] entry of its superclass. */
class PremiumAccount(number: String) : SavingsAccount(number) {
    fun upgrade(): Nothing = throw IllegalStateException("upgrade")
}
