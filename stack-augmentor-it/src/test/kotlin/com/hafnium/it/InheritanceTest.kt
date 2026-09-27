package com.hafnium.it

import com.hafnium.it.inheritance.ExpressOrder
import com.hafnium.it.inheritance.Order
import com.hafnium.it.inheritance.OrderService
import com.hafnium.it.inheritance.Priority
import com.hafnium.it.inheritance.RushOrder
import com.hafnium.it.inheritance.TrackedOrder
import com.hafnium.it.inheritance.patterned.Customer
import net.bytebuddy.ByteBuddy
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.implementation.SuperMethodCall
import net.bytebuddy.matcher.ElementMatchers.isDeclaredBy
import net.bytebuddy.matcher.ElementMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Answers
import org.mockito.MockMakers
import org.mockito.Mockito
import org.springframework.cglib.core.SpringNamingPolicy
import org.springframework.cglib.proxy.Enhancer
import org.springframework.cglib.proxy.NoOp

/**
 * [augment.receiver] entries apply to the classes they match, not to subclasses: see the inheritance entries in
 * src/test/resources/stack-augmentor.toml. A frame's receiver id comes from the entry of the class that declares its
 * method, read from the object: a subclass, e.g. a proxy or a mock, shows the id of Order in the frames of Order's
 * methods, but its own methods are only instrumented with an entry of its own. Runs with the agent.
 */
class InheritanceTest {

    private val order = "com.hafnium.it.inheritance.Order"
    private val customer = "com.hafnium.it.inheritance.patterned.Customer"

    /** The top frame: the method that threw. */
    private fun frame(block: () -> Unit): StackTraceElement = assertThrows<IllegalStateException> { block() }.stackTrace[0]

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
        assertEquals("$order{id=o-1}", frame { Order("o-1").ship() }.className)
    }

    @Test
    fun `a subclass without an entry of its own`() {
        // Its own methods are not instrumented, ...
        assertEquals("com.hafnium.it.inheritance.RushOrder", frame { RushOrder("r-1").expedite() }.className)
        // ... but the methods it inherits from Order show the id of Order's entry.
        assertEquals("$order{id=r-1}", frame { RushOrder("r-1").ship() }.className)
    }

    @Test
    fun `a subclass with an entry of its own finds the member in its superclass`() {
        assertEquals("com.hafnium.it.inheritance.ExpressOrder{id=e-1}", frame { ExpressOrder("e-1").express() }.className)
        assertEquals("$order{id=e-1}", frame { ExpressOrder("e-1").ship() }.className)
    }

    @Test
    fun `each frame uses the entry of the class declaring its method`() {
        val tracked = TrackedOrder("t-1", "TR-9")
        assertEquals("com.hafnium.it.inheritance.TrackedOrder{tracking=TR-9}", frame { tracked.track() }.className)
        assertEquals("$order{id=t-1}", frame { tracked.ship() }.className)
    }

    @Test
    fun `an anonymous subclass`() {
        val anonymous = object : Order("a-1") {}
        assertTrue(anonymous.javaClass.name.startsWith("com.hafnium.it.InheritanceTest"), anonymous.javaClass.name)
        assertEquals("$order{id=a-1}", frame { anonymous.ship() }.className)
    }

    @Test
    fun `an enum constant with a body`() {
        assertEquals("com.hafnium.it.inheritance.Priority{code=l}", frame { Priority.LOW.describe() }.className)
        // HIGH is an instance of the subclass Priority$HIGH: Priority's methods show its id, ...
        assertEquals("com.hafnium.it.inheritance.Priority{code=h}", frame { Priority.HIGH.describe() }.className)
        // ... while its own override is not instrumented.
        assertEquals("com.hafnium.it.inheritance.Priority\$HIGH", frame { Priority.HIGH.escalate() }.className)
    }

    @Test
    fun `a Hibernate-style ByteBuddy proxy`() {
        val proxy = generatedSubclass(Order::class.java, "$order\$HibernateProxy\$h1", "h-1")
        assertEquals("$order{id=h-1}", frame { proxy.ship() }.className)
        // The proxy's own override is not instrumented: its frame, below Order's, is unchanged.
        assertEquals("$order\$HibernateProxy\$h1", assertThrows<IllegalStateException> { proxy.ship() }.stackTrace[1].className)

        // A pattern matches the proxy's name too, so both frames show the id.
        val patterned = generatedSubclass(Customer::class.java, "$customer\$HibernateProxy\$h2", "c-1")
        val trace = assertThrows<IllegalStateException> { patterned.rename() }.stackTrace
        assertEquals("$customer{code=c-1}", trace[0].className)
        assertEquals("$customer\$HibernateProxy\$h2{code=c-1}", trace[1].className)
    }

    @Test
    fun `a Spring CGLIB proxy`() {
        val proxy = cglibProxy(Order::class.java, "s-1")
        assertTrue(proxy.javaClass.name.startsWith("$order\$\$SpringCGLIB\$\$"), proxy.javaClass.name)
        assertEquals("$order{id=s-1}", frame { proxy.ship() }.className)

        val patterned = cglibProxy(Customer::class.java, "c-2")
        assertEquals("$customer{code=c-2}", frame { patterned.rename() }.className)
    }

    @Test
    fun `a Mockito spy from the subclass mock maker`() {
        val spy = Mockito.mock(
            Order::class.java,
            Mockito.withSettings().mockMaker(MockMakers.SUBCLASS).spiedInstance(Order("m-1")).defaultAnswer(Answers.CALLS_REAL_METHODS),
        )
        assertTrue(spy.javaClass.name.startsWith("$order\$MockitoMock\$"), spy.javaClass.name)
        // Mockito copies the spied instance's fields into the spy, so the id is there.
        assertEquals("$order{id=m-1}", frame { spy.ship() }.className)
    }

    @Test
    fun `a Mockito spy from the inline mock maker`() {
        // The inline mock maker changes Order itself instead of subclassing it: the spy's class is Order.
        val spy = Mockito.spy(Order("m-2"))
        assertEquals(Order::class.java, spy.javaClass)
        assertEquals("$order{id=m-2}", frame { spy.ship() }.className)
    }

    @Test
    fun `arguments of a subclass show their text`() {
        // An argument has no declaring class: its runtime class decides, and RushOrder has no entry.
        assertEquals("process{order=o-3}", frame { OrderService().process(Order("o-3")) }.methodName)
        assertEquals("process{order=Order#r-3}", frame { OrderService().process(RushOrder("r-3")) }.methodName)
        assertEquals("process{order=e-3}", frame { OrderService().process(ExpressOrder("e-3")) }.methodName)
    }
}
