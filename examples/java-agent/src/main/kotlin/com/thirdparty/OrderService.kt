package com.thirdparty

// Stand-ins for library classes: no annotations, ids come from stack-augmentor.toml.

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
        require(quantity > 0) { "quantity must be positive, was $quantity" }
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
