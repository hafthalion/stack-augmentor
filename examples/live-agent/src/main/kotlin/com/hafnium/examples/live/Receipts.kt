package com.hafnium.examples.live

import com.hafnium.stackaugmentor.StackTraceParams

abstract class Document @StackTraceParams constructor(val title: String, total: Double) {
    init {
        require(total >= 0) { "A document cannot have a negative total: $total" }
    }
}

/** Calls the Document constructor with super(...), which throws for a negative total. */
class Receipt @StackTraceParams constructor(order: Order) : Document("Receipt for $order", order.total) {
    val text = "$title: ${order.total}"
}
