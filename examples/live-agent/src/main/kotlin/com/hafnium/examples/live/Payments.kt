package com.hafnium.examples.live

import com.hafnium.stackaugmentor.StackTraceId
import com.hafnium.stackaugmentor.StackTraceParam
import com.hafnium.stackaugmentor.StackTraceParams

class Checkout(
    @StackTraceId private val order: Order,
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

class PaymentClient(@StackTraceId private val endpoint: String) {
    /** The card number is shown hashed. */
    @StackTraceParams
    fun charge(@StackTraceParam(secret = true) card: String, amount: Double) {
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
