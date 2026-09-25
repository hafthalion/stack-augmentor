package com.hafnium

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        printStackTraceOf { ClassWithAnnotation("object-1").method(ObjectParam("object-param-1")) }
        printStackTraceOf { ClassWithoutAnnotation("object-2").method() }
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
