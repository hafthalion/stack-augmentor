package com.hafnium.examples.agent

import com.thirdparty.Customer
import com.thirdparty.InventoryAudit
import com.thirdparty.InventoryService
import com.thirdparty.Order
import com.thirdparty.OrderService
import com.thirdparty.UserService

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        // direct annotation
        printStackTraceOf { ClassWithAnnotation("object-1").method(ObjectParam("object-param-1")) }
        printStackTraceOf { ClassWithAnnotation("object-1").transfer("a", "b", 10) }
        printStackTraceOf { ClassWithoutAnnotation("object-2").method("123") }

        // thirdparty classes without annotations
        printStackTraceOf { OrderService().process(Order(4711), 3, "rush") }
        printStackTraceOf { Customer("c-9").rename() }
        printStackTraceOf { InventoryService().reserve("x-1", 2) }
        // "-": no parameter ids, although the Inventory* entry matches too
        printStackTraceOf { InventoryAudit().record("x-1", 2) }
        // "#": the email is shown hashed
        printStackTraceOf { UserService().invite("ann", "ann@example.com") }
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
