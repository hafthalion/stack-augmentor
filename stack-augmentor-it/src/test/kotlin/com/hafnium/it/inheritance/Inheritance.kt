package com.hafnium.it.inheritance

// Receiver ids by inheritance, see InheritanceTest. Only the classes whose comment names an entry have one in
// src/test/resources/stack-augmentor.toml. The classes are open, so that proxies and mocks can subclass them.

/** Entry "id". */
open class Order(private val id: String) {
    open fun ship(): Nothing = throw IllegalStateException("cannot ship $id")

    override fun toString() = "Order#$id"

    /** An inner class is a class of its own, Order$Line: Order's entry does not match its name. */
    inner class Line {
        fun cancel(): Nothing = throw IllegalStateException("cannot cancel a line of $id")
    }
}

/** No entry of its own. */
class RushOrder(id: String) : Order(id) {
    fun expedite(): Nothing = throw IllegalStateException("cannot expedite")
}

/** Entry "id": its own, naming the field that Order declares. */
open class ExpressOrder(id: String) : Order(id) {
    fun express(): Nothing = throw IllegalStateException("cannot express")
}

/** Entry "tracking": its own, naming a different field. */
class TrackedOrder(id: String, private val tracking: String) : Order(id) {
    fun track(): Nothing = throw IllegalStateException("cannot track $tracking")
}

/** No entry: a subclass of a subclass. */
class SameDayOrder(id: String) : ExpressOrder(id) {
    fun hurry(): Nothing = throw IllegalStateException("cannot hurry")
}

/** Entry "note": overrides ship() and calls Order's. */
class GiftOrder(id: String, private val note: String) : Order(id) {
    override fun ship(): Nothing = super.ship()
}

/** No entry: overrides ship() and calls Order's. */
class ReturnOrder(id: String) : Order(id) {
    override fun ship(): Nothing = super.ship()
}

/** Entry "-": no receiver id for its own methods. */
class DiscontinuedOrder(id: String) : Order(id) {
    fun discontinue(): Nothing = throw IllegalStateException("cannot discontinue")
}

/** No entry: its subclass Invoice has one. */
open class Document(protected val number: String) {
    fun print(): Nothing = throw IllegalStateException("cannot print $number")
}

/** Entry "number", naming the field that Document declares. */
class Invoice(number: String) : Document(number) {
    fun pay(): Nothing = throw IllegalStateException("cannot pay $number")
}

/** Entry "id": abstract, with a concrete and an abstract method. */
abstract class Shipment(private val id: String) {
    fun load(): Nothing = throw IllegalStateException("cannot load $id")

    abstract fun route(): Nothing
}

/** No entry: implements Shipment's abstract method. */
class SeaShipment(id: String) : Shipment(id) {
    override fun route(): Nothing = throw IllegalStateException("no route")
}

/** Entry "label()": an interface with a default method. */
interface Labeled {
    fun label(): String

    fun relabel(): Nothing = throw IllegalStateException("cannot relabel ${label()}")
}

/** Entry "code": implements Labeled. */
class Parcel(private val code: String) : Labeled {
    override fun label() = "parcel-$code"
}

/** No entry: implements Labeled. */
class Crate(private val code: String) : Labeled {
    override fun label() = "crate-$code"
}

/** Entry "name": generic, so that a subclass's override of save() gets a bridge method. */
open class Repository<T>(private val name: String) {
    open fun save(item: T): Nothing = throw IllegalStateException("cannot save $item in $name")
}

/** No entry: overrides save(Order), and the compiler adds the bridge save(Object). */
class OrderRepository : Repository<Order>("orders") {
    override fun save(item: Order): Nothing = super.save(item)
}

/** Entry "code". */
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
