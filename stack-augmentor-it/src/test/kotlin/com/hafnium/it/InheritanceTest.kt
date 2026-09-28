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
import com.hafnium.it.inheritance.ranked.Account
import com.hafnium.it.inheritance.ranked.excluded.Dropped
import com.hafnium.it.inheritance.ranked.excluded.Kept
import com.hafnium.it.inheritance.ranked.special.Tagged
import net.bytebuddy.ByteBuddy
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.implementation.SuperMethodCall
import net.bytebuddy.matcher.ElementMatchers.isDeclaredBy
import net.bytebuddy.matcher.ElementMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.assertThrows
import org.mockito.Answers
import org.mockito.MockMakers
import org.mockito.Mockito
import org.springframework.cglib.core.SpringNamingPolicy
import org.springframework.cglib.proxy.Enhancer
import org.springframework.cglib.proxy.NoOp
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path

/**
 * [augment.receiver] entries apply to the classes they match, not to subclasses: see the inheritance entries in
 * src/test/resources/stack-augmentor.toml. A frame's receiver id comes from the entry of the class that declares its
 * method, read from the object: a subclass, e.g. a proxy or a mock, shows the id of Order in the frames of Order's
 * methods, but its own methods are only instrumented with an entry of its own. Runs with the agent.
 *
 * Each test makes one call. Its frames, as the test observed them, and the receiver's types and the declarations of
 * the called method, as reflection reports them, are written to build/reports/inheritance/observed.json for review.
 */
class InheritanceTest {

    private val order = "com.hafnium.it.inheritance.Order"
    private val customer = "com.hafnium.it.inheritance.patterned.Customer"

    private lateinit var category: String
    private lateinit var test: String

    @BeforeEach
    fun name(info: TestInfo) {
        category = info.testClass.map { it.getAnnotation(DisplayName::class.java)?.value ?: it.simpleName }.orElse("")
        test = info.displayName.removeSuffix("()")
    }

    /**
     * The frames of the exception that [call] throws on [receiver], recorded for the report together with what
     * reflection says about the receiver: its class hierarchy, and which classes declare [method].
     */
    private fun <T : Any> trace(call: String, receiver: T, method: String, block: (T) -> Unit): Array<StackTraceElement> {
        val trace = assertThrows<IllegalStateException> { block(receiver) }.stackTrace
        // The frames down to the test's own code.
        val frames = trace.takeWhile { !it.className.startsWith(InheritanceTest::class.java.name) }
        observations += Observation(
            category,
            test,
            call,
            frames.take(8).map { "${it.className}.${it.methodName}" },
            hierarchy(receiver.javaClass),
            types(receiver.javaClass),
            declarations(receiver.javaClass, method),
        )
        return trace
    }

    /** The class, its superclasses up to Object, and the interfaces that each of them implements directly. */
    private fun hierarchy(type: Class<*>): List<String> =
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }.map { c ->
            if (c.interfaces.isEmpty()) c.name else c.name + " implements " + c.interfaces.joinToString(", ") { it.name }
        }.toList()

    /**
     * The receiver's class, its superclasses and their interfaces, outside the JDK, each as its kind, its name and its
     * direct supertypes, e.g. "open class com.acme.RushOrder : com.acme.Order".
     */
    private fun types(type: Class<*>): List<String> =
        hierarchyTypes(type).filter { !it.name.startsWith("java.") }.map { c ->
            val kind = when {
                c.isInterface -> "interface"
                c.isEnum || c.superclass?.isEnum == true -> "enum"
                Modifier.isAbstract(c.modifiers) -> "abstract class"
                Modifier.isFinal(c.modifiers) -> "class"
                else -> "open class"
            }
            val supertypes = (listOfNotNull(c.superclass?.takeIf { it != Any::class.java }) + c.interfaces).map { it.name }
            "$kind ${c.name}" + if (supertypes.isEmpty()) "" else " : " + supertypes.joinToString(", ")
        }

    /** The class, then its superclasses, each followed by the interfaces it implements, directly or not. */
    private fun hierarchyTypes(type: Class<*>): Set<Class<*>> {
        val types = LinkedHashSet<Class<*>>()
        generateSequence(type) { it.superclass }.forEach { c ->
            types += c
            generateSequence(c.interfaces.toList()) { level -> level.flatMap { it.interfaces.toList() }.ifEmpty { null } }.forEach { types += it }
        }
        return types
    }

    /** Every class and interface in the hierarchy that declares a method of that name, the class first. */
    private fun declarations(type: Class<*>, method: String): List<String> {
        return hierarchyTypes(type).flatMap { c ->
            c.declaredMethods.filter { it.name == method }.map { m ->
                val kind = listOfNotNull(
                    "bridge".takeIf { m.isBridge },
                    "synthetic".takeIf { m.isSynthetic && !m.isBridge },
                    "abstract".takeIf { Modifier.isAbstract(m.modifiers) },
                    "default".takeIf { m.isDefault },
                    "static".takeIf { Modifier.isStatic(m.modifiers) },
                    "final".takeIf { Modifier.isFinal(m.modifiers) },
                )
                c.name + "." + m.name + "(" + m.parameterTypes.joinToString(", ") { it.simpleName } + ")" +
                    (if (kind.isEmpty()) "" else " [" + kind.joinToString(", ") + "]")
            }
        }
    }

    /** The top frame: the method that threw. */
    private fun <T : Any> frame(call: String, receiver: T, method: String, block: (T) -> Unit): StackTraceElement =
        trace(call, receiver, method, block)[0]

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

    @Nested
    @DisplayName("The class with the entry")
    inner class MatchedClass {

        @Test
        fun `a method of the class with the entry shows its id`() {
            assertEquals("$order{id=o-1}", frame("Order(\"o-1\").ship()", Order("o-1"), "ship") { it.ship() }.className)
        }

        @Test
        fun `a method of an enum with the entry shows the constant's id`() {
            assertEquals("com.hafnium.it.inheritance.Priority{code=l}", frame("Priority.LOW.describe()", Priority.LOW, "describe") { it.describe() }.className)
        }
    }

    @Nested
    @DisplayName("Subclasses without an entry")
    inner class SubclassWithoutEntry {

        @Test
        fun `a subclass's own method is not instrumented`() {
            assertEquals("com.hafnium.it.inheritance.RushOrder", frame("RushOrder(\"r-1\").expedite()", RushOrder("r-1"), "expedite") { it.expedite() }.className)
        }

        @Test
        fun `a method inherited from the superclass shows the superclass's id`() {
            assertEquals("$order{id=r-1}", frame("RushOrder(\"r-1\").ship()", RushOrder("r-1"), "ship") { it.ship() }.className)
        }

        @Test
        fun `a method inherited by an anonymous subclass shows the superclass's id`() {
            val anonymous = object : Order("a-1") {}
            assertTrue(anonymous.javaClass.name.startsWith("com.hafnium.it.InheritanceTest"), anonymous.javaClass.name)
            assertEquals("$order{id=a-1}", frame("object : Order(\"a-1\") {}.ship()", anonymous, "ship") { it.ship() }.className)
        }

        @Test
        fun `a method inherited by an enum constant with a body shows the enum's id`() {
            // HIGH is an instance of the subclass Priority$HIGH.
            assertEquals("com.hafnium.it.inheritance.Priority{code=h}", frame("Priority.HIGH.describe()", Priority.HIGH, "describe") { it.describe() }.className)
        }

        @Test
        fun `an enum constant's own override is not instrumented`() {
            assertEquals("com.hafnium.it.inheritance.Priority\$HIGH", frame("Priority.HIGH.escalate()", Priority.HIGH, "escalate") { it.escalate() }.className)
        }

        @Test
        fun `an inner class is not matched by the entry of its outer class`() {
            assertEquals("$order\$Line", frame("Order(\"o-5\").Line().cancel()", Order("o-5").Line(), "cancel") { it.cancel() }.className)
        }
    }

    @Nested
    @DisplayName("Subclasses of subclasses")
    inner class SubclassOfSubclass {

        @Test
        fun `the own method of a subclass without an entry is not instrumented`() {
            assertEquals("com.hafnium.it.inheritance.SameDayOrder", frame("SameDayOrder(\"d-1\").hurry()", SameDayOrder("d-1"), "hurry") { it.hurry() }.className)
        }

        @Test
        fun `a method inherited from the middle class shows the middle class's id`() {
            assertEquals("com.hafnium.it.inheritance.ExpressOrder{id=d-1}", frame("SameDayOrder(\"d-1\").express()", SameDayOrder("d-1"), "express") { it.express() }.className)
        }

        @Test
        fun `a method inherited from the top class shows the top class's id`() {
            assertEquals("$order{id=d-1}", frame("SameDayOrder(\"d-1\").ship()", SameDayOrder("d-1"), "ship") { it.ship() }.className)
        }
    }

    @Nested
    @DisplayName("Subclasses with an entry of their own")
    inner class SubclassWithEntry {

        @Test
        fun `an own method shows the subclass's id, read from a field the superclass declares`() {
            assertEquals("com.hafnium.it.inheritance.ExpressOrder{id=e-1}", frame("ExpressOrder(\"e-1\").express()", ExpressOrder("e-1"), "express") { it.express() }.className)
        }

        @Test
        fun `an inherited method shows the superclass's id, not the subclass's`() {
            assertEquals("$order{id=e-1}", frame("ExpressOrder(\"e-1\").ship()", ExpressOrder("e-1"), "ship") { it.ship() }.className)
        }

        @Test
        fun `an own method shows the subclass's own member`() {
            val tracked = TrackedOrder("t-1", "TR-9")
            assertEquals("com.hafnium.it.inheritance.TrackedOrder{tracking=TR-9}", frame("TrackedOrder(\"t-1\", \"TR-9\").track()", tracked, "track") { it.track() }.className)
        }

        @Test
        fun `an inherited method shows the superclass's member, not the subclass's`() {
            val tracked = TrackedOrder("t-1", "TR-9")
            assertEquals("$order{id=t-1}", frame("TrackedOrder(\"t-1\", \"TR-9\").ship()", tracked, "ship") { it.ship() }.className)
        }

        @Test
        fun `a "-" entry leaves the subclass's own method unchanged`() {
            assertEquals(
                "com.hafnium.it.inheritance.DiscontinuedOrder",
                frame("DiscontinuedOrder(\"z-1\").discontinue()", DiscontinuedOrder("z-1"), "discontinue") { it.discontinue() }.className,
            )
        }

        @Test
        fun `a "-" entry does not affect the methods it inherits`() {
            assertEquals("$order{id=z-1}", frame("DiscontinuedOrder(\"z-1\").ship()", DiscontinuedOrder("z-1"), "ship") { it.ship() }.className)
        }
    }

    @Nested
    @DisplayName("Superclasses without an entry")
    inner class SuperclassWithoutEntry {

        @Test
        fun `an own method of a subclass with an entry shows its id`() {
            assertEquals("com.hafnium.it.inheritance.Invoice{number=i-1}", frame("Invoice(\"i-1\").pay()", Invoice("i-1"), "pay") { it.pay() }.className)
        }

        @Test
        fun `a method inherited from a superclass without an entry is not instrumented`() {
            // Although Invoice's entry names Document's field.
            assertEquals("com.hafnium.it.inheritance.Document", frame("Invoice(\"i-1\").print()", Invoice("i-1"), "print") { it.print() }.className)
        }
    }

    @Nested
    @DisplayName("Abstract superclasses")
    inner class AbstractSuperclass {

        @Test
        fun `a concrete method of an abstract class with an entry shows its id`() {
            assertEquals("com.hafnium.it.inheritance.Shipment{id=s-9}", frame("SeaShipment(\"s-9\").load()", SeaShipment("s-9"), "load") { it.load() }.className)
        }

        @Test
        fun `the implementation of an abstract method in a subclass without an entry is not instrumented`() {
            assertEquals("com.hafnium.it.inheritance.SeaShipment", frame("SeaShipment(\"s-9\").route()", SeaShipment("s-9"), "route") { it.route() }.className)
        }
    }

    @Nested
    @DisplayName("Overrides that call the overridden method")
    inner class Overrides {

        @Test
        fun `an override with an entry shows its own id, and the overridden method the superclass's`() {
            val trace = trace("GiftOrder(\"g-1\", \"wrapped\").ship()", GiftOrder("g-1", "wrapped"), "ship") { it.ship() }
            assertEquals("$order{id=g-1}", trace[0].className)
            assertEquals("com.hafnium.it.inheritance.GiftOrder{note=wrapped}", trace[1].className)
        }

        @Test
        fun `an override without an entry is unchanged, and the overridden method shows the superclass's id`() {
            val trace = trace("ReturnOrder(\"x-1\").ship()", ReturnOrder("x-1"), "ship") { it.ship() }
            assertEquals("$order{id=x-1}", trace[0].className)
            assertEquals("com.hafnium.it.inheritance.ReturnOrder", trace[1].className)
        }

        @Test
        fun `a generic override and its bridge method without an entry are unchanged`() {
            val repository: Repository<Order> = OrderRepository()
            val trace = trace("(OrderRepository() as Repository<Order>).save(Order(\"o-9\"))", repository, "save") { it.save(Order("o-9")) }
            assertEquals("com.hafnium.it.inheritance.Repository{name=orders}", trace[0].className)
            val own = trace.filter { it.className.startsWith("com.hafnium.it.inheritance.OrderRepository") }
            assertTrue(own.isNotEmpty() && own.all { it.className == "com.hafnium.it.inheritance.OrderRepository" }, own.toString())
        }
    }

    @Nested
    @DisplayName("Interfaces")
    inner class Interfaces {

        @Test
        fun `a default method shows the id of the implementing class's entry, not the interface's`() {
            val trace = trace("Parcel(\"p1\").relabel()", Parcel("p1"), "relabel") { it.relabel() }
            assertEquals("com.hafnium.it.inheritance.Labeled{code=p1}", trace[0].className)
            // Kotlin compiles a method into Parcel that calls the default method.
            assertEquals("com.hafnium.it.inheritance.Parcel{code=p1}", trace[1].className)
        }

        @Test
        fun `a default method is unchanged when the implementing class has no entry`() {
            val trace = trace("Crate(\"c1\").relabel()", Crate("c1"), "relabel") { it.relabel() }
            assertEquals("com.hafnium.it.inheritance.Labeled", trace[0].className)
            assertEquals("com.hafnium.it.inheritance.Crate", trace[1].className)
        }
    }

    @Nested
    @DisplayName("Pattern entries")
    inner class Patterns {

        @Test
        fun `a subclass in the pattern's package inherits a method that shows the superclass's id`() {
            assertEquals("$customer{code=v-1}", frame("VipCustomer(\"v-1\").rename()", VipCustomer("v-1"), "rename") { it.rename() }.className)
        }

        @Test
        fun `a subclass in the pattern's package gets its own id`() {
            assertEquals(
                "com.hafnium.it.inheritance.patterned.VipCustomer{code=v-1}",
                frame("VipCustomer(\"v-1\").upgrade()", VipCustomer("v-1"), "upgrade") { it.upgrade() }.className,
            )
        }

        @Test
        fun `a subclass outside the pattern's package inherits a method that shows the superclass's id`() {
            assertEquals("$customer{code=l-1}", frame("LocalCustomer(\"l-1\").rename()", LocalCustomer("l-1"), "rename") { it.rename() }.className)
        }

        @Test
        fun `a subclass outside the pattern's package is not instrumented`() {
            assertEquals(
                "com.hafnium.it.inheritance.LocalCustomer",
                frame("LocalCustomer(\"l-1\").relocate()", LocalCustomer("l-1"), "relocate") { it.relocate() }.className,
            )
        }
    }

    @Nested
    @DisplayName("Pattern precedence")
    inner class PatternPrecedence {

        private val ranked = "com.hafnium.it.inheritance.ranked"

        @Test
        fun `a class matched only by a broad pattern shows its id`() {
            assertEquals("$ranked.Account{code=a-1}", frame("Account(\"a-1\").close()", Account("a-1"), "close") { it.close() }.className)
        }

        @Test
        fun `a more specific "-" pattern wins over a broader pattern`() {
            assertEquals("$ranked.excluded.Dropped", frame("Dropped(\"x-1\").drop()", Dropped("x-1"), "drop") { it.drop() }.className)
        }

        @Test
        fun `a method inherited by a class excluded by a "-" pattern shows the superclass's id`() {
            assertEquals("$ranked.Account{code=x-1}", frame("Dropped(\"x-1\").close()", Dropped("x-1"), "close") { it.close() }.className)
        }

        @Test
        fun `an entry without wildcards wins over a "-" pattern`() {
            assertEquals("$ranked.excluded.Kept{code=k-1}", frame("Kept(\"k-1\").keep()", Kept("k-1"), "keep") { it.keep() }.className)
        }

        @Test
        fun `a more specific pattern wins over a broader one`() {
            assertEquals("$ranked.special.Tagged{tag=vip}", frame("Tagged(\"t-1\", \"vip\").label()", Tagged("t-1", "vip"), "label") { it.label() }.className)
        }

        @Test
        fun `a method inherited from a class matched by the broader pattern shows that pattern's id`() {
            assertEquals("$ranked.Account{code=t-1}", frame("Tagged(\"t-1\", \"vip\").close()", Tagged("t-1", "vip"), "close") { it.close() }.className)
        }
    }

    @Nested
    @DisplayName("Framework proxies and mocks")
    inner class Proxies {

        @Test
        fun `a ByteBuddy proxy shows the superclass's id, and its own override is unchanged`() {
            val proxy = generatedSubclass(Order::class.java, "$order\$HibernateProxy\$h1", "h-1")
            val trace = trace("Order\$HibernateProxy\$h1(\"h-1\").ship()", proxy, "ship") { it.ship() }
            assertEquals("$order{id=h-1}", trace[0].className)
            assertEquals("$order\$HibernateProxy\$h1", trace[1].className)
        }

        @Test
        fun `a ByteBuddy proxy matched by a pattern shows the id in its own override too`() {
            val proxy = generatedSubclass(Customer::class.java, "$customer\$HibernateProxy\$h2", "c-1")
            val trace = trace("Customer\$HibernateProxy\$h2(\"c-1\").rename()", proxy, "rename") { it.rename() }
            assertEquals("$customer{code=c-1}", trace[0].className)
            assertEquals("$customer\$HibernateProxy\$h2{code=c-1}", trace[1].className)
        }

        @Test
        fun `a Spring CGLIB proxy shows the superclass's id`() {
            val proxy = cglibProxy(Order::class.java, "s-1")
            assertTrue(proxy.javaClass.name.startsWith("$order\$\$SpringCGLIB\$\$"), proxy.javaClass.name)
            assertEquals("$order{id=s-1}", frame("Order\$\$SpringCGLIB\$\$0(\"s-1\").ship()", proxy, "ship") { it.ship() }.className)
        }

        @Test
        fun `a Spring CGLIB proxy of a class matched by a pattern shows its id`() {
            val proxy = cglibProxy(Customer::class.java, "c-2")
            assertEquals("$customer{code=c-2}", frame("Customer\$\$SpringCGLIB\$\$0(\"c-2\").rename()", proxy, "rename") { it.rename() }.className)
        }

        @Test
        fun `a Mockito spy from the subclass mock maker shows the spied class's id`() {
            val spy = Mockito.mock(
                Order::class.java,
                Mockito.withSettings().mockMaker(MockMakers.SUBCLASS).spiedInstance(Order("m-1")).defaultAnswer(Answers.CALLS_REAL_METHODS),
            )
            assertTrue(spy.javaClass.name.startsWith("$order\$MockitoMock\$"), spy.javaClass.name)
            // Mockito copies the spied instance's fields into the spy, so the id is there.
            assertEquals("$order{id=m-1}", frame("Mockito subclass spy of Order(\"m-1\").ship()", spy, "ship") { it.ship() }.className)
        }

        @Test
        fun `a Mockito spy from the inline mock maker shows the spied class's id`() {
            // The inline mock maker changes Order itself instead of subclassing it: the spy's class is Order.
            val spy = Mockito.spy(Order("m-2"))
            assertEquals(Order::class.java, spy.javaClass)
            assertEquals("$order{id=m-2}", frame("Mockito.spy(Order(\"m-2\")).ship()", spy, "ship") { it.ship() }.className)
        }
    }

    @Nested
    @DisplayName("Arguments")
    inner class Arguments {

        @Test
        fun `an argument of the class with the entry shows its toString()`() {
            assertEquals("process{order=Order#o-3}", frame("OrderService().process(Order(\"o-3\"))", OrderService(), "process") { it.process(Order("o-3")) }.methodName)
        }

        @Test
        fun `an argument of a subclass without an entry shows its toString()`() {
            assertEquals("process{order=Order#r-3}", frame("OrderService().process(RushOrder(\"r-3\"))", OrderService(), "process") { it.process(RushOrder("r-3")) }.methodName)
        }

        @Test
        fun `an argument of a subclass with an entry shows its toString(), not its id`() {
            assertEquals("process{order=Order#e-3}", frame("OrderService().process(ExpressOrder(\"e-3\"))", OrderService(), "process") { it.process(ExpressOrder("e-3")) }.methodName)
        }
    }

    private class Observation(
        val category: String,
        val test: String,
        val call: String,
        val frames: List<String>,
        val hierarchy: List<String>,
        val types: List<String>,
        val declarations: List<String>,
    )

    companion object {
        private val observations = mutableListOf<Observation>()

        private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun list(items: List<String>) = items.joinToString(", ", "[", "]") { json(it) }

        @JvmStatic
        @AfterAll
        fun writeObservations() {
            val file = Path.of("build/reports/inheritance/observed.json")
            Files.createDirectories(file.parent)
            Files.writeString(file, observations.joinToString(",\n", "[\n", "\n]\n") { o ->
                "{\"category\": ${json(o.category)}, \"test\": ${json(o.test)}, \"call\": ${json(o.call)}, \"frames\": ${list(o.frames)}, " +
                    "\"hierarchy\": ${list(o.hierarchy)}, \"types\": ${list(o.types)}, \"declarations\": ${list(o.declarations)}}"
            })
        }
    }
}
