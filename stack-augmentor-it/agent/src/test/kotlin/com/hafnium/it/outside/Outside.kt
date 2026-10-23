package com.hafnium.it.outside

import com.hafnium.stackaugmentor.Augmented
import com.hafnium.stackaugmentor.AugmentedParam
import com.hafnium.stackaugmentor.AugmentedParams
import com.hafnium.stackaugmentor.AugmentedReceiver
import com.thirdparty.Customer
import com.thirdparty.SavingsAccount

// Classes outside the fixtures: @Augmented alone decides where their annotations are read.

/** Its parameter annotations are read: it has @Augmented. */
@Augmented
class MethodAnnotationsOutside {
    fun run(@AugmentedParam code: Int): Nothing = throw IllegalStateException("run $code")
}

/** Its @AugmentedParams is read: it has @Augmented. */
@Augmented
class ClassParamsViaMethods {
    @AugmentedParams
    fun run(a: Int, b: String): Nothing = throw IllegalStateException("run $a $b")
}

/**
 * Its superclass SavingsAccount has an [augment.receiver] entry, a pattern, which does not apply to it; the
 * [augment.params] entry "SavingsAccount+::transfer" does, with "+".
 */
class PremiumAccount(number: String) : SavingsAccount(number) {
    fun transfer(amount: Int): Nothing = throw IllegalStateException("cannot transfer $amount")
}

/** Its superclass's entry "com.thirdparty.Customer::*" has no "+": it applies to the methods VipCustomer inherits only. */
class VipCustomer(customerId: String) : Customer(customerId) {
    fun upgrade(): Nothing = throw IllegalStateException("upgrade")
}

/** @Augmented on a method: only that method is augmented by the annotations, not track. */
class Dispatcher(@AugmentedReceiver private val hub: String) {
    @Augmented
    fun send(@AugmentedParam order: String): Nothing = throw IllegalStateException("cannot send $order")

    fun track(@AugmentedParam order: String): Nothing = throw IllegalStateException("cannot track $order")
}

/** @Augmented(false) on the type: its methods are not augmented, unless their own @Augmented says so. */
@Augmented(false)
class Muted(@AugmentedReceiver private val code: String) {
    fun quiet(@AugmentedParam x: Int): Nothing = throw IllegalStateException("quiet $x")

    @Augmented
    fun loud(@AugmentedParam x: Int): Nothing = throw IllegalStateException("loud $x")
}

/** @Augmented on the type: all its methods, except one with @Augmented(false). */
@Augmented
class Selective(@AugmentedReceiver private val code: String) {
    fun run(@AugmentedParam x: Int): Nothing = throw IllegalStateException("run $x")

    @Augmented(false)
    fun skip(@AugmentedParam x: Int): Nothing = throw IllegalStateException("skip $x")
}

/** It has no @Augmented: its annotations are ignored. */
class WithoutAugmented(@AugmentedReceiver private val code: String) {
    fun run(@AugmentedParam x: Int): Nothing = throw IllegalStateException("run $x")
}
