package com.thirdparty

// Stand-ins for library classes: no annotations, ids come from stack-augmentor.toml.

class Order(private val orderNumber: Long) {
    fun getOrderNumber(): Long = orderNumber
}

class Customer(private val customerId: String) {
    fun rename(): Nothing = throw IllegalStateException("cannot rename")
}

class OrderService {
    fun process(order: Order, quantity: Int, note: String): Nothing = throw IllegalStateException("cannot process")
}
