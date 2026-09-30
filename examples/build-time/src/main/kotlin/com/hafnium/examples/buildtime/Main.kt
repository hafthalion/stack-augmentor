package com.hafnium.examples.buildtime

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        printStackTraceOf { ClassWithAnnotation("object-1").method(ObjectParam("object-param-1")) }
        printStackTraceOf { ClassWithoutAnnotation("object-2").method() }
        printStackTraceOf { Shipping().ship("o-17", 2) }
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
