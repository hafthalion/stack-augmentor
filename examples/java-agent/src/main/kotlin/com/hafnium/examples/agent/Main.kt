package com.hafnium.examples.agent

import com.hafnium.stackaugmentor.ExceptionFormat
import com.thirdparty.InventoryAudit
import com.thirdparty.InventoryService
import com.thirdparty.Order
import com.thirdparty.OrderService

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        // Your own classes: annotations, and an exact entry beating the "@" pattern
        printStackTraceOf { ClassWithoutAnnotation("object-2").method("123") }

        // Third-party classes without annotations: everything from stack-augmentor.toml
        printStackTraceOf { OrderService(InventoryService(InventoryAudit())).process(Order(4711), 3, "rush") }

        // An IllegalArgumentException, which [augment.exceptions] excludes: its frames show no ids
        printStackTraceOf { OrderService(InventoryService(InventoryAudit())).process(Order(4713), 0, "rush") }

        // A simple cause chain: the frames the cause shares with the wrapper show the same ids in both, so
        // "... N more" still collapses them. The cause's submit frame, where submit caught it, shows them too.
        try {
            Shop("shop-1").buy(4712)
        } catch (e: CheckoutException) {
            e.printStackTrace(System.out)
            println()
            // The same chain root cause first, as Logback's and Log4j 2's %rEx print it
            print(ExceptionFormat.rootCauseFirst(e))
            println()
        }
    }

    private inline fun printStackTraceOf(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // To standard output, so that the traces and the blank lines between them stay in order
            e.printStackTrace(System.out)
            println()
        }
    }
}
