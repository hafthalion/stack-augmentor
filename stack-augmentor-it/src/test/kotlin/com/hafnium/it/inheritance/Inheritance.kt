package com.hafnium.it.inheritance

// Receiver ids by inheritance, see InheritanceTest. Only these classes have [augment.receiver] entries:
// "com.hafnium.it.inheritance.Order" = "id", "...ExpressOrder" = "id", "...TrackedOrder" = "tracking" and
// "...Priority" = "code". The classes are open, so that proxies and mocks can subclass them.

open class Order(private val id: String) {
    open fun ship(): Nothing = throw IllegalStateException("cannot ship $id")

    override fun toString() = "Order#$id"
}

/** No entry of its own. */
class RushOrder(id: String) : Order(id) {
    fun expedite(): Nothing = throw IllegalStateException("cannot expedite")
}

/** An entry of its own, naming the field that Order declares. */
class ExpressOrder(id: String) : Order(id) {
    fun express(): Nothing = throw IllegalStateException("cannot express")
}

/** An entry of its own, naming a different field. */
class TrackedOrder(id: String, private val tracking: String) : Order(id) {
    fun track(): Nothing = throw IllegalStateException("cannot track $tracking")
}

enum class Priority(private val code: String) {
    LOW("l"),

    /** A constant with a body is a subclass of the enum: Priority$HIGH. */
    HIGH("h") {
        override fun escalate(): Nothing = throw IllegalStateException("already high")
    };

    open fun escalate(): Nothing = throw IllegalStateException("cannot escalate $code")

    fun describe(): Nothing = throw IllegalStateException("cannot describe $code")
}

class OrderService {
    fun process(order: Order): Nothing = throw IllegalStateException("cannot process $order")
}
