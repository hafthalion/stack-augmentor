package com.hafnium.examples.live

import com.hafnium.stackaugmentor.Augmented
import com.hafnium.stackaugmentor.AugmentedReceiver
import com.hafnium.stackaugmentor.AugmentedParams

/** Answers each request with a status, and logs the exceptions that end one. */
@Augmented
class Shop(
    @AugmentedReceiver private val name: String,
    private val payments: PaymentClient,
) {
    @AugmentedParams
    fun handle(user: String, path: String) {
        val status = try {
            val session = Session(user, payments)
            val orderNumber = path.substringAfterLast('/').toInt()
            if (path.startsWith("/receipt/")) session.receipt(orderNumber) else session.checkout(orderNumber)
        } catch (e: Exception) {
            log("$path failed", e)
            "500 Internal Server Error"
        }
        println("$user $path -> $status")
        println()
    }
}

@Augmented
class Session(
    @AugmentedReceiver private val user: String,
    private val payments: PaymentClient,
) {
    @AugmentedParams
    fun checkout(orderNumber: Int): String =
        if (Checkout(ORDERS.getValue(orderNumber), payments).pay()) "200 OK" else "402 Payment Required"

    @AugmentedParams
    fun receipt(orderNumber: Int): String = Receipt(ORDERS.getValue(orderNumber)).text
}

/** Shown with its toString() where it is a receiver member or a parameter value. */
class Order(val number: Int, val total: Double, val card: String) {
    override fun toString() = "Order#$number"
}

private val ORDERS = listOf(
    Order(4711, 99.90, "4000-0000-0000-0002"), // declined
    Order(4712, 2499.00, "4111-1111-1111-1111"), // slow: over 1000
    Order(4713, -20.00, "4111-1111-1111-1111"), // a refund: no receipt for a negative total
).associateBy { it.number }

/** Logs to stdout, so the output keeps its order under Gradle. */
fun log(message: String, e: Throwable) {
    println("WARN $message")
    e.printStackTrace(System.out)
}
