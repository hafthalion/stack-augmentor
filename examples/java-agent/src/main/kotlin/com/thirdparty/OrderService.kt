package com.thirdparty

// Stand-ins for library classes: no annotations, ids come from stack-augmentor.toml.

class Order(private val orderNumber: Long) {
    fun getOrderNumber(): Long = orderNumber

    /** Shown when an order is an argument. */
    override fun toString() = "Order#$orderNumber"
}

class Customer(private val customerId: String) {
    fun rename(): Nothing = throw IllegalStateException("cannot rename")
}

class InventoryService {
    fun reserve(sku: String, count: Int): Nothing = throw IllegalStateException("cannot reserve")
}

/** Matched by the Inventory* entry, but its own "-" entry is more specific: no parameter ids. */
class InventoryAudit {
    fun record(sku: String, count: Int): Nothing = throw IllegalStateException("cannot record")
}

class UserService {
    fun invite(user: String, email: String): Nothing = throw IllegalStateException("cannot invite")
}

class OrderService {
    fun process(order: Order, quantity: Int, note: String): Nothing = throw IllegalStateException("cannot process")
}
