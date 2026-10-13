package com.hafnium.it.outside

import com.hafnium.it.fixtures.Base
import com.hafnium.stackaugmentor.StackTraceAugment
import com.hafnium.stackaugmentor.StackTraceParam
import com.hafnium.stackaugmentor.StackTraceParams
import com.thirdparty.Customer
import com.thirdparty.SavingsAccount

// No [augment.receiver] entry matches these classes, and entries of their superclasses do not apply to them: their
// @StackTraceId is ignored. Their parameter annotations are ignored, unless an [augment.params] "@" entry applies.

class DerivedOutside : Base() {
    fun fail(): Nothing = throw IllegalStateException("outside")
}

class ParamsOnly {
    fun withParam(@StackTraceParam code: Int): Nothing = throw IllegalStateException("code $code")
}

class AllParamsOutside {
    @StackTraceParams
    fun run(x: Int): Nothing = throw IllegalStateException("run $x")
}

/** Its parameter annotations are enabled by an [augment.params] "@" entry. */
class MethodAnnotationsOutside {
    fun run(@StackTraceParam code: Int): Nothing = throw IllegalStateException("run $code")
}

/** Its @StackTraceParams is enabled by an [augment.params] "@" entry for all its methods. */
class ClassParamsViaMethods {
    @StackTraceParams
    fun run(a: Int, b: String): Nothing = throw IllegalStateException("run $a $b")
}

/**
 * Its superclass SavingsAccount has an [augment.receiver] entry, a pattern, which does not apply to it; the
 * [augment.params] entry "SavingsAccount+::transfer" does, with "+".
 */
class PremiumAccount(number: String) : SavingsAccount(number) {
    fun upgrade(): Nothing = throw IllegalStateException("upgrade")

    fun transfer(amount: Int): Nothing = throw IllegalStateException("cannot transfer $amount")
}

/** Its superclass's entry "com.thirdparty.Customer::*" has no "+": it applies to the methods VipCustomer inherits only. */
class VipCustomer(customerId: String) : Customer(customerId) {
    fun upgrade(): Nothing = throw IllegalStateException("upgrade")
}

/**
 * Selected by "@" after "::" in both tables: "Dispatcher::@" in [augment.receiver] applies to its @StackTraceAugment methods
 * only, "Dispatcher::@, track" in [augment.params] to them and to track.
 */
class Dispatcher(private val hub: String) {
    @StackTraceAugment
    fun send(order: String): Nothing = throw IllegalStateException("cannot send $order")

    fun track(order: String): Nothing = throw IllegalStateException("cannot track $order")
}
