package com.hafnium.it.outside

import com.hafnium.it.fixtures.Base
import com.hafnium.stackaugmentor.Augmented
import com.hafnium.stackaugmentor.AugmentedParam
import com.hafnium.stackaugmentor.AugmentedParams
import com.hafnium.stackaugmentor.AugmentedReceiver
import com.thirdparty.Customer
import com.thirdparty.SavingsAccount

// No [augment.annotations] entry selects these classes, unless named below, and entries of their superclasses do not
// apply to them: their annotations are ignored.

class DerivedOutside : Base() {
    fun fail(): Nothing = throw IllegalStateException("outside")
}

class ParamsOnly {
    fun withParam(@AugmentedParam code: Int): Nothing = throw IllegalStateException("code $code")
}

class AllParamsOutside {
    @AugmentedParams
    fun run(x: Int): Nothing = throw IllegalStateException("run $x")
}

/** Its parameter annotations are read: an [augment.annotations] entry names the class. */
class MethodAnnotationsOutside {
    fun run(@AugmentedParam code: Int): Nothing = throw IllegalStateException("run $code")
}

/** Its @AugmentedParams is read: an [augment.annotations] entry names the class. */
class ClassParamsViaMethods {
    @AugmentedParams
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

/** A false [augment.annotations] entry comes before the pattern that would read its annotations. */
class Unread(@AugmentedReceiver private val code: String) {
    fun run(@AugmentedParam x: Int): Nothing = throw IllegalStateException("run $x")
}
