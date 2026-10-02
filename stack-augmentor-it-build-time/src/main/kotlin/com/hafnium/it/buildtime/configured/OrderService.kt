package com.hafnium.it.buildtime.configured

// Classes without annotations, as if they could not be annotated: their ids come from stack-augmentor.toml.

class Order(private val orderNumber: Long) {
    fun getOrderNumber(): Long = orderNumber

    fun ship(customer: Customer) {
        customer.notify("ann@example.com")
    }

    /** Shown when an order is an argument. */
    override fun toString() = "Order#$orderNumber"
}

class Customer(private val customerId: String) {
    fun notify(email: String): Nothing = throw IllegalStateException("cannot notify")
}

class OrderService(private val inventory: InventoryService) {
    fun process(order: Order, quantity: Int, note: String) {
        inventory.reserve("x-1", quantity, order)
    }
}

class InventoryService(private val audit: InventoryAudit) {
    fun reserve(sku: String, count: Int, order: Order) {
        audit.record(sku, count, order)
    }
}

/** Matched by the Inventory* entry, but its own "-" entry is more specific: no parameter ids. */
class InventoryAudit {
    fun record(sku: String, count: Int, order: Order) {
        order.ship(Customer("c-9"))
    }
}

/** Constructors are selected by "<init>": parameter ids, never a receiver id. */
class Shipment(orderId: Long, weight: Double, note: String) {
    init {
        require(weight > 0) { "weight" }
    }
}

open class Box(val weight: Double)

/** Computing the argument of super(...) throws, before the call: covered too. */
class Crate(orderId: Long, weight: Double) : Box(weight.also { require(it > 0) { "weight" } })
