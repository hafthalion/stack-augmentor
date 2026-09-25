package com.hafnium

import com.thirdparty.Customer
import com.thirdparty.Order
import com.thirdparty.OrderService

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        // direct annotation
        printStackTraceOf { ObjectWithAnnotation("object-1").objectMethod(ObjectParam("object-param-1")) }
        printStackTraceOf { ObjectWithoutAnnotation("object-2").objectMethod() }

        // thirdparty classes without annotations
        printStackTraceOf { OrderService().process(Order(4711), 3, "rush") }
        printStackTraceOf { Customer("c-9").rename() }
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
