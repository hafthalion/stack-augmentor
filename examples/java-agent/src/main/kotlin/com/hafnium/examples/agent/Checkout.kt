package com.hafnium.examples.agent

import com.hafnium.stackaugmentor.StackTraceId
import com.hafnium.stackaugmentor.StackTraceParam
import com.thirdparty.InventoryAudit
import com.thirdparty.InventoryService
import com.thirdparty.Order
import com.thirdparty.OrderService

/**
 * A simple cause chain: [submit] wraps the third-party exception in one of its own. The frames below it, [Shop.buy]
 * and main, are in both stack traces and show the same ids in both.
 */
class Checkout(@StackTraceId private val checkoutId: String) {
    fun submit(@StackTraceParam order: Order) {
        try {
            OrderService(InventoryService(InventoryAudit())).process(order, 1, "standard")
        } catch (e: IllegalStateException) {
            throw CheckoutException("checkout of $order failed", e)
        }
    }
}

class CheckoutException(message: String, cause: Throwable) : Exception(message, cause)

/** Calls the checkout: a frame that the wrapper and its cause share. */
class Shop(@StackTraceId private val shopId: String) {
    fun buy(@StackTraceParam orderNumber: Long) {
        Checkout("checkout-7").submit(Order(orderNumber))
    }
}
