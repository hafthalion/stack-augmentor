package com.hafnium.examples.buildtime

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
    }

    private inline fun printStackTraceOf(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            e.printStackTrace()
            println()
        }
    }
}
