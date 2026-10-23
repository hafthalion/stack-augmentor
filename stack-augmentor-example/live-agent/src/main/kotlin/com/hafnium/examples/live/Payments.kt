package com.hafnium.examples.live

import com.hafnium.stackaugmentor.Augmented
import com.hafnium.stackaugmentor.AugmentedReceiver
import com.hafnium.stackaugmentor.AugmentedParam
import com.hafnium.stackaugmentor.AugmentedParams

@Augmented
class Checkout(
    @AugmentedReceiver private val order: Order,
    private val payments: PaymentClient,
) {
    /** A declined payment is logged here and not passed on. */
    fun pay(): Boolean = try {
        payments.charge(order.card, order.total)
        true
    } catch (e: PaymentDeclinedException) {
        log("Payment of $order declined", e)
        false
    }
}

@Augmented
class PaymentClient(@AugmentedReceiver private val endpoint: String) {
    /** The card number is shown hashed. */
    @AugmentedParams
    fun charge(@AugmentedParam(secret = true) card: String, amount: Double) {
        if (card.startsWith("4000")) {
            throw PaymentDeclinedException("Card declined")
        }
        if (amount > 1000) {
            // Not thrown: it only records where the slow call came from.
            log("Slow payment", SlowCallWarning("Fraud check took 1800 ms"))
        }
    }
}

class PaymentDeclinedException(message: String) : Exception(message)

class SlowCallWarning(message: String) : Exception(message)
