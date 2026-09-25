package com.thirdparty

// Stand-ins for library classes: no annotations, configured in stack-augmentor.toml.

class Invoice(private val invoiceNumber: String) {
    fun getInvoiceNumber(): String = invoiceNumber
}

class InvoicePrinter {
    val printerId = "dummy-printer"

    fun print(invoice: Invoice): Unit = throw IllegalStateException("printer offline")
}
