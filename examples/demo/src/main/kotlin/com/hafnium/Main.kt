package com.hafnium

import com.thirdparty.Invoice
import com.thirdparty.InvoicePrinter

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            InvoicePrinter().print(Invoice("INV-2026-001"))
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            println()
        }

        ObjectClass("object-1").objectMethod(42)
    }
}
