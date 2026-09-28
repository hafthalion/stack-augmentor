package com.hafnium.it

import com.hafnium.it.inheritance.Crate
import com.hafnium.it.inheritance.DiscontinuedOrder
import com.hafnium.it.inheritance.ExpressOrder
import com.hafnium.it.inheritance.GiftOrder
import com.hafnium.it.inheritance.Invoice
import com.hafnium.it.inheritance.LocalCustomer
import com.hafnium.it.inheritance.Order
import com.hafnium.it.inheritance.OrderRepository
import com.hafnium.it.inheritance.OrderService
import com.hafnium.it.inheritance.Parcel
import com.hafnium.it.inheritance.Priority
import com.hafnium.it.inheritance.Repository
import com.hafnium.it.inheritance.ReturnOrder
import com.hafnium.it.inheritance.RushOrder
import com.hafnium.it.inheritance.SameDayOrder
import com.hafnium.it.inheritance.SeaShipment
import com.hafnium.it.inheritance.TrackedOrder
import com.hafnium.it.inheritance.patterned.Customer
import com.hafnium.it.inheritance.patterned.VipCustomer
import net.bytebuddy.ByteBuddy
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.implementation.SuperMethodCall
import net.bytebuddy.matcher.ElementMatchers.isDeclaredBy
import net.bytebuddy.matcher.ElementMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.assertThrows
import org.mockito.Answers
import org.mockito.MockMakers
import org.mockito.Mockito
import org.springframework.cglib.core.SpringNamingPolicy
import org.springframework.cglib.proxy.Enhancer
import org.springframework.cglib.proxy.NoOp
import java.nio.file.Files
import java.nio.file.Path

/**
 * [augment.receiver] entries apply to the classes they match, not to subclasses: see the inheritance entries in
 * src/test/resources/stack-augmentor.toml. A frame's receiver id comes from the entry of the class that declares its
 * method, read from the object: a subclass, e.g. a proxy or a mock, shows the id of Order in the frames of Order's
 * methods, but its own methods are only instrumented with an entry of its own. Runs with the agent.
 *
 * Every call's frames, as the test observed them, are written to build/reports/inheritance/observed.json for review.
 */
class InheritanceTest {

    private val order = "com.hafnium.it.inheritance.Order"
    private val customer = "com.hafnium.it.inheritance.patterned.Customer"

    private lateinit var test: String

    @BeforeEach
    fun name(info: TestInfo) {
        test = info.displayName.removeSuffix("()")
    }

    /** The frames of the exception that [call] throws, recorded for the report. */
    private fun trace(call: String, block: () -> Unit): Array<StackTraceElement> {
        val trace = assertThrows<IllegalStateException> { block() }.stackTrace
        // The frames down to the test's own code.
        val frames = trace.takeWhile { it.className != InheritanceTest::class.java.name && !it.methodName.contains("lambda") }
        observations += Observation(test, call, frames.take(6).map { "${it.className}.${it.methodName}" })
        return trace
    }

    /** The top frame: the method that threw. */
    private fun frame(call: String, block: () -> Unit): StackTraceElement = trace(call, block)[0]

    /** A subclass generated at runtime, as ByteBuddy-based frameworks (e.g. Hibernate) create them, under this name. */
    private fun <T> generatedSubclass(type: Class<T>, name: String, id: String): T =
        ByteBuddy()
            .subclass(type)
            .name(name)
            // Like a proxy: override the methods, and call the real ones.
            .method(not(isDeclaredBy(Any::class.java)))
            .intercept(SuperMethodCall.INSTANCE)
            .make()
            .load(type.classLoader, ClassLoadingStrategy.Default.WRAPPER)
            .loaded
            .getConstructor(String::class.java)
            .newInstance(id)

    private fun <T> cglibProxy(type: Class<T>, id: String): T {
        // Configured as Spring configures it for its proxies, e.g. of @Configuration or @Transactional classes.
        val enhancer = Enhancer()
        enhancer.namingPolicy = SpringNamingPolicy.INSTANCE
        enhancer.setSuperclass(type)
        enhancer.setCallback(NoOp.INSTANCE)
        @Suppress("UNCHECKED_CAST")
        return enhancer.create(arrayOf(String::class.java), arrayOf(id)) as T
    }

    @Test
    fun `the matched class itself`() {
        assertEquals("$order{id=o-1}", frame("Order(\"o-1\").ship()") { Order("o-1").ship() }.className)
    }

    @Test
    fun `a subclass without an entry of its own`() {
        // Its own methods are not instrumented, ...
        assertEquals("com.hafnium.it.inheritance.RushOrder", frame("RushOrder(\"r-1\").expedite()") { RushOrder("r-1").expedite() }.className)
        // ... but the methods it inherits from Order show the id of Order's entry.
        assertEquals("$order{id=r-1}", frame("RushOrder(\"r-1\").ship()") { RushOrder("r-1").ship() }.className)
    }

    @Test
    fun `a subclass with an entry of its own finds the member in its superclass`() {
        assertEquals("com.hafnium.it.inheritance.ExpressOrder{id=e-1}", frame("ExpressOrder(\"e-1\").express()") { ExpressOrder("e-1").express() }.className)
        assertEquals("$order{id=e-1}", frame("ExpressOrder(\"e-1\").ship()") { ExpressOrder("e-1").ship() }.className)
    }

    @Test
    fun `each frame uses the entry of the class declaring its method`() {
        val tracked = TrackedOrder("t-1", "TR-9")
        assertEquals("com.hafnium.it.inheritance.TrackedOrder{tracking=TR-9}", frame("TrackedOrder(\"t-1\", \"TR-9\").track()") { tracked.track() }.className)
        assertEquals("$order{id=t-1}", frame("TrackedOrder(\"t-1\", \"TR-9\").ship()") { tracked.ship() }.className)
    }

    @Test
    fun `an anonymous subclass`() {
        val anonymous = object : Order("a-1") {}
        assertTrue(anonymous.javaClass.name.startsWith("com.hafnium.it.InheritanceTest"), anonymous.javaClass.name)
        assertEquals("$order{id=a-1}", frame("object : Order(\"a-1\") {}.ship()") { anonymous.ship() }.className)
    }

    @Test
    fun `an enum constant with a body`() {
        assertEquals("com.hafnium.it.inheritance.Priority{code=l}", frame("Priority.LOW.describe()") { Priority.LOW.describe() }.className)
        // HIGH is an instance of the subclass Priority$HIGH: Priority's methods show its id, ...
        assertEquals("com.hafnium.it.inheritance.Priority{code=h}", frame("Priority.HIGH.describe()") { Priority.HIGH.describe() }.className)
        // ... while its own override is not instrumented.
        assertEquals("com.hafnium.it.inheritance.Priority\$HIGH", frame("Priority.HIGH.escalate()") { Priority.HIGH.escalate() }.className)
    }

    @Test
    fun `a Hibernate-style ByteBuddy proxy`() {
        val proxy = generatedSubclass(Order::class.java, "$order\$HibernateProxy\$h1", "h-1")
        assertEquals("$order{id=h-1}", frame("Order\$HibernateProxy\$h1(\"h-1\").ship()") { proxy.ship() }.className)
        // The proxy's own override is not instrumented: its frame, below Order's, is unchanged.
        assertEquals("$order\$HibernateProxy\$h1", trace("Order\$HibernateProxy\$h1(\"h-1\").ship()") { proxy.ship() }[1].className)

        // A pattern matches the proxy's name too, so both frames show the id.
        val patterned = generatedSubclass(Customer::class.java, "$customer\$HibernateProxy\$h2", "c-1")
        val trace = trace("Customer\$HibernateProxy\$h2(\"c-1\").rename()") { patterned.rename() }
        assertEquals("$customer{code=c-1}", trace[0].className)
        assertEquals("$customer\$HibernateProxy\$h2{code=c-1}", trace[1].className)
    }

    @Test
    fun `a Spring CGLIB proxy`() {
        val proxy = cglibProxy(Order::class.java, "s-1")
        assertTrue(proxy.javaClass.name.startsWith("$order\$\$SpringCGLIB\$\$"), proxy.javaClass.name)
        assertEquals("$order{id=s-1}", frame("Order\$\$SpringCGLIB\$\$0(\"s-1\").ship()") { proxy.ship() }.className)

        val patterned = cglibProxy(Customer::class.java, "c-2")
        assertEquals("$customer{code=c-2}", frame("Customer\$\$SpringCGLIB\$\$0(\"c-2\").rename()") { patterned.rename() }.className)
    }

    @Test
    fun `a Mockito spy from the subclass mock maker`() {
        val spy = Mockito.mock(
            Order::class.java,
            Mockito.withSettings().mockMaker(MockMakers.SUBCLASS).spiedInstance(Order("m-1")).defaultAnswer(Answers.CALLS_REAL_METHODS),
        )
        assertTrue(spy.javaClass.name.startsWith("$order\$MockitoMock\$"), spy.javaClass.name)
        // Mockito copies the spied instance's fields into the spy, so the id is there.
        assertEquals("$order{id=m-1}", frame("Mockito subclass spy of Order(\"m-1\").ship()") { spy.ship() }.className)
    }

    @Test
    fun `a Mockito spy from the inline mock maker`() {
        // The inline mock maker changes Order itself instead of subclassing it: the spy's class is Order.
        val spy = Mockito.spy(Order("m-2"))
        assertEquals(Order::class.java, spy.javaClass)
        assertEquals("$order{id=m-2}", frame("Mockito.spy(Order(\"m-2\")).ship()") { spy.ship() }.className)
    }

    @Test
    fun `a subclass of a subclass`() {
        assertEquals("com.hafnium.it.inheritance.SameDayOrder", frame("SameDayOrder(\"d-1\").hurry()") { SameDayOrder("d-1").hurry() }.className)
        assertEquals("com.hafnium.it.inheritance.ExpressOrder{id=d-1}", frame("SameDayOrder(\"d-1\").express()") { SameDayOrder("d-1").express() }.className)
        assertEquals("$order{id=d-1}", frame("SameDayOrder(\"d-1\").ship()") { SameDayOrder("d-1").ship() }.className)
    }

    @Test
    fun `an override with an entry of its own calls the overridden method`() {
        val trace = trace("GiftOrder(\"g-1\", \"wrapped\").ship()") { GiftOrder("g-1", "wrapped").ship() }
        assertEquals("$order{id=g-1}", trace[0].className)
        assertEquals("com.hafnium.it.inheritance.GiftOrder{note=wrapped}", trace[1].className)
    }

    @Test
    fun `an override without an entry calls the overridden method`() {
        val trace = trace("ReturnOrder(\"x-1\").ship()") { ReturnOrder("x-1").ship() }
        assertEquals("$order{id=x-1}", trace[0].className)
        assertEquals("com.hafnium.it.inheritance.ReturnOrder", trace[1].className)
    }

    @Test
    fun `a subclass with a "-" entry`() {
        assertEquals(
            "com.hafnium.it.inheritance.DiscontinuedOrder",
            frame("DiscontinuedOrder(\"z-1\").discontinue()") { DiscontinuedOrder("z-1").discontinue() }.className,
        )
        // "-" only applies to its own methods: Order's entry still decides in Order's.
        assertEquals("$order{id=z-1}", frame("DiscontinuedOrder(\"z-1\").ship()") { DiscontinuedOrder("z-1").ship() }.className)
    }

    @Test
    fun `a superclass without an entry`() {
        assertEquals("com.hafnium.it.inheritance.Invoice{number=i-1}", frame("Invoice(\"i-1\").pay()") { Invoice("i-1").pay() }.className)
        // Document's method is not instrumented, though Invoice's entry names Document's field.
        assertEquals("com.hafnium.it.inheritance.Document", frame("Invoice(\"i-1\").print()") { Invoice("i-1").print() }.className)
    }

    @Test
    fun `an abstract superclass`() {
        assertEquals("com.hafnium.it.inheritance.Shipment{id=s-9}", frame("SeaShipment(\"s-9\").load()") { SeaShipment("s-9").load() }.className)
        // The implementation of Shipment's abstract method is SeaShipment's own, which has no entry.
        assertEquals("com.hafnium.it.inheritance.SeaShipment", frame("SeaShipment(\"s-9\").route()") { SeaShipment("s-9").route() }.className)
    }

    @Test
    fun `an interface's default method`() {
        // The interface is not a superclass: the entry of the object's class decides, not Labeled's "label()".
        assertEquals("com.hafnium.it.inheritance.Labeled{code=p1}", frame("Parcel(\"p1\").relabel()") { Parcel("p1").relabel() }.className)
        assertEquals("com.hafnium.it.inheritance.Labeled", frame("Crate(\"c1\").relabel()") { Crate("c1").relabel() }.className)
    }

    @Test
    fun `an override of a generic method, with a bridge method`() {
        val repository: Repository<Order> = OrderRepository()
        val trace = trace("(OrderRepository() as Repository<Order>).save(Order(\"o-9\"))") { repository.save(Order("o-9")) }
        assertEquals("com.hafnium.it.inheritance.Repository{name=orders}", trace[0].className)
        // OrderRepository has no entry: neither its override nor the bridge method are changed.
        val own = trace.filter { it.className.startsWith("com.hafnium.it.inheritance.OrderRepository") }
        assertTrue(own.isNotEmpty() && own.all { it.className == "com.hafnium.it.inheritance.OrderRepository" }, own.toString())
    }

    @Test
    fun `an inner class`() {
        assertEquals("$order\$Line", frame("Order(\"o-5\").Line().cancel()") { Order("o-5").Line().cancel() }.className)
    }

    @Test
    fun `a pattern matches subclasses whose names it matches`() {
        assertEquals("$customer{code=v-1}", frame("VipCustomer(\"v-1\").rename()") { VipCustomer("v-1").rename() }.className)
        assertEquals(
            "com.hafnium.it.inheritance.patterned.VipCustomer{code=v-1}",
            frame("VipCustomer(\"v-1\").upgrade()") { VipCustomer("v-1").upgrade() }.className,
        )
        // A subclass in another package: the pattern does not match its name.
        assertEquals("$customer{code=l-1}", frame("LocalCustomer(\"l-1\").rename()") { LocalCustomer("l-1").rename() }.className)
        assertEquals(
            "com.hafnium.it.inheritance.LocalCustomer",
            frame("LocalCustomer(\"l-1\").relocate()") { LocalCustomer("l-1").relocate() }.className,
        )
    }

    @Test
    fun `arguments show their text, whatever their class's entry`() {
        assertEquals("process{order=Order#o-3}", frame("OrderService().process(Order(\"o-3\"))") { OrderService().process(Order("o-3")) }.methodName)
        assertEquals("process{order=Order#r-3}", frame("OrderService().process(RushOrder(\"r-3\"))") { OrderService().process(RushOrder("r-3")) }.methodName)
        assertEquals("process{order=Order#e-3}", frame("OrderService().process(ExpressOrder(\"e-3\"))") { OrderService().process(ExpressOrder("e-3")) }.methodName)
    }

    private class Observation(val test: String, val call: String, val frames: List<String>)

    companion object {
        private val observations = mutableListOf<Observation>()

        private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        @JvmStatic
        @AfterAll
        fun writeObservations() {
            val file = Path.of("build/reports/inheritance/observed.json")
            Files.createDirectories(file.parent)
            Files.writeString(file, observations.joinToString(",\n", "[\n", "\n]\n") { o ->
                "{\"test\": ${json(o.test)}, \"call\": ${json(o.call)}, \"frames\": [${o.frames.joinToString(", ") { json(it) }}]}"
            })
        }
    }
}
