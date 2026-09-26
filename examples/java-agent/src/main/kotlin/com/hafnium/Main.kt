package com.hafnium

import com.thirdparty.Customer
import com.thirdparty.InventoryService
import com.thirdparty.Order
import com.thirdparty.OrderService

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        // direct annotation
        printStackTraceOf { ClassWithAnnotation("object-1").method(ObjectParam("object-param-1")) }
        printStackTraceOf { ClassWithAnnotation("object-1").transfer("a", "b", 10) }
        printStackTraceOf { ClassWithoutAnnotation("object-2").method() }

        // thirdparty classes without annotations
        printStackTraceOf { OrderService().process(Order(4711), 3, "rush") }
        printStackTraceOf { Customer("c-9").rename() }
        printStackTraceOf { InventoryService().reserve("x-1", 2) }
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
