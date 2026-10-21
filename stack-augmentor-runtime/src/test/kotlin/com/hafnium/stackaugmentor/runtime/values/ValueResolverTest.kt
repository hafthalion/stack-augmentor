package com.hafnium.stackaugmentor.runtime.values

import com.hafnium.stackaugmentor.AugmentedReceiver
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig
import com.hafnium.stackaugmentor.runtime.config.ReceiverSpec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValueResolverTest {

    class Annotated {
        @AugmentedReceiver
        val objectId = "a-1"

        @Suppress("unused")
        private val customerId = "c-1"
    }

    class ByMethod {
        @AugmentedReceiver
        fun computeKey() = "k-${1 + 1}"
    }

    class ConstructorProperty(@AugmentedReceiver val code: String)

    class Unannotated(private val customerId: String) {
        override fun toString() = "Unannotated($customerId)"
    }

    class Throwing {
        @AugmentedReceiver
        fun id(): String = throw IllegalStateException("no id")
    }

    open class BaseWithReceiver {
        @AugmentedReceiver
        val baseId = "b1"
    }

    class ChildOfBase : BaseWithReceiver()

    open class MultiAnnotated {
        @AugmentedReceiver
        val tenant = "acme"

        @AugmentedReceiver
        val orderId = 42

        @AugmentedReceiver
        fun region() = "eu"

        @AugmentedReceiver
        fun channel() = "web"
    }

    class ChildOfMulti : MultiAnnotated() {
        @AugmentedReceiver
        val lineId = 7
    }

    enum class Color { RED }

    data class Point(val x: Int)

    /** Kotlin properties without a backing field: only their getters exist. */
    class Computed {
        val total get() = "t-${1 + 1}"
        val isActive get() = true
    }

    class WithArrayReceiver {
        @AugmentedReceiver
        val codes = intArrayOf(1, 2)
    }

    /** All classes of this test are in this package. */
    private val here = ValueResolverTest::class.java.packageName + ".**"

    /** [augment.annotations] entries that are true. */
    private fun reads(vararg classes: String) = classes.associateWith { true }

    private val originalErr = System.err

    @AfterEach
    fun restore() = System.setErr(originalErr)

    /**
     * An [ValueResolver] that picks the receiver entry as the instrumentation would: the first [augment.receiver] entry
     * matching the declaring class, whatever methods it names, or without one the annotations, where
     * [augment.annotations] selects the class.
     */
    private class Resolver(private val config: AugmentorConfig) {
        private val resolver = ValueResolver(config)

        fun receiverValues(target: Any, declaringClass: String = target.javaClass.name): List<NamedValue> {
            val owner = ValueResolver.declaringClass(target.javaClass, declaringClass)
            val key = config.classEntries(owner).firstOrNull()?.key()
                ?: AugmentorConfig.ANNOTATIONS.takeIf { config.readsAnnotations(owner) }
                ?: return listOf()
            return resolver.receiverValues(target, declaringClass, key)
        }

        /** The only receiver value, or null. */
        fun receiverValue(target: Any, declaringClass: String = target.javaClass.name): NamedValue? =
            receiverValues(target, declaringClass).also { assertTrue(it.size <= 1, "$it") }.singleOrNull()

        fun paramValue(value: Any?) = resolver.paramValue(value)
    }

    private fun config(vararg classes: Pair<String, ReceiverSpec>, annotations: Map<String, Boolean> = mapOf()) =
        AugmentorConfig.builder().classes(classes.associate { (key, spec) -> (if ("::" in key) key else "$key::*") to spec })
            .annotations(annotations).maxValueLength(10).exceptions(mapOf("**" to true)).build()

    /** A key without methods gets "::*", all methods. */
    private fun resolver(vararg classes: Pair<String, ReceiverSpec>, annotations: Map<String, Boolean> = mapOf()) =
        Resolver(config(*classes, annotations = annotations))

    private fun name(type: Class<*>) = type.name

    @Test
    fun `the annotations key reads the annotations, whatever the annotations table says`() {
        // The instrumentation decided it, e.g. at build time with another configuration.
        val annotated = Annotated()
        assertEquals(listOf(NamedValue("objectId", "a-1")), ValueResolver(config()).receiverValues(annotated, name(Annotated::class.java), AugmentorConfig.ANNOTATIONS))
        // A key that the configuration does not have gives nothing.
        assertEquals(listOf<NamedValue>(), ValueResolver(config()).receiverValues(annotated, name(Annotated::class.java), name(Annotated::class.java) + "::*"))
    }

    @Test
    fun `every annotated member gives a value, fields first, then methods by name, nearest class first`() {
        val resolver = resolver(annotations = reads(here))
        assertEquals(
            listOf(NamedValue("tenant", "acme"), NamedValue("orderId", "42"), NamedValue("channel", "web"), NamedValue("region", "eu")),
            resolver.receiverValues(MultiAnnotated()),
        )
        assertEquals(listOf("lineId", "tenant", "orderId", "channel", "region"), resolver.receiverValues(ChildOfMulti()).map { it.name() })
    }

    @Test
    fun `a list of members gives one value each, in the configured order`() {
        val list = ReceiverSpec.MemberList(listOf(ReceiverSpec.FieldSpec("customerId"), ReceiverSpec.MethodSpec("toString")))
        assertEquals(
            listOf(NamedValue("customerId", "c-7"), NamedValue("toString", "Unannotat…")),
            resolver(name(Unannotated::class.java) to list).receiverValues(Unannotated("c-7")),
        )
    }

    @Test
    fun `a missing member of a list is reported and left out`() {
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val list = ReceiverSpec.MemberList(listOf(ReceiverSpec.FieldSpec("nope"), ReceiverSpec.FieldSpec("customerId")))
        assertEquals(listOf(NamedValue("customerId", "c-1")), resolver(name(Annotated::class.java) to list).receiverValues(Annotated()))
        assertTrue(err.toString().contains("no field or property nope found"), err.toString())
    }

    @Test
    fun `annotated field, method and constructor property`() {
        val resolver = resolver(annotations = reads(here))
        assertEquals(NamedValue("objectId", "a-1"), resolver.receiverValue(Annotated()))
        assertEquals(NamedValue("computeKey", "k-2"), resolver.receiverValue(ByMethod()))
        assertEquals(NamedValue("code", "X"), resolver.receiverValue(ConstructorProperty("X")))
    }

    @Test
    fun `annotations are only used where an annotations entry selects the class`() {
        assertNull(resolver().receiverValue(Annotated()))
        assertNull(resolver(annotations = reads("com.acme.**")).receiverValue(Annotated()))
        assertEquals(NamedValue("objectId", "a-1"), resolver(annotations = reads(name(Annotated::class.java))).receiverValue(Annotated()))
    }

    @Test
    fun `the first matching entry wins`() {
        // An [augment.receiver] entry decides before the annotations.
        val exact = resolver(name(Annotated::class.java) to ReceiverSpec.FieldSpec("customerId"), annotations = reads(here))
        assertEquals(NamedValue("customerId", "c-1"), exact.receiverValue(Annotated()))
        // The first matching [augment.annotations] entry decides, false included.
        val first = resolver(annotations = linkedMapOf(name(Annotated::class.java) to false, here to true))
        assertNull(first.receiverValue(Annotated()))
        assertEquals(NamedValue("computeKey", "k-2"), first.receiverValue(ByMethod()))
    }

    @Test
    fun `a class name with plus applies to subclasses, without plus or a pattern it does not, and members are found in superclasses`() {
        assertEquals(NamedValue("baseId", "b1"), resolver(annotations = reads(name(BaseWithReceiver::class.java) + "+")).receiverValue(ChildOfBase()))
        assertEquals(NamedValue("baseId", "b1"), resolver(name(BaseWithReceiver::class.java) + "+" to ReceiverSpec.FieldSpec("baseId")).receiverValue(ChildOfBase()))
        assertNull(resolver(annotations = reads(name(BaseWithReceiver::class.java))).receiverValue(ChildOfBase()))
        assertNull(resolver(annotations = reads(name(BaseWithReceiver::class.java).dropLast(1) + "?")).receiverValue(ChildOfBase()))
        // An entry of the subclass itself finds the annotation or the field that the superclass declares.
        assertEquals(NamedValue("baseId", "b1"), resolver(annotations = reads(name(ChildOfBase::class.java))).receiverValue(ChildOfBase()))
        assertEquals(NamedValue("baseId", "b1"), resolver(name(ChildOfBase::class.java) to ReceiverSpec.FieldSpec("baseId")).receiverValue(ChildOfBase()))
    }

    @Test
    fun `the class declaring the method decides, read from the object`() {
        val resolver = resolver(name(BaseWithReceiver::class.java) + "+" to ReceiverSpec.FieldSpec("baseId"))
        // A method that BaseWithReceiver declares, running on a ChildOfBase: the entry of BaseWithReceiver applies.
        assertEquals(NamedValue("baseId", "b1"), resolver.receiverValue(ChildOfBase(), name(BaseWithReceiver::class.java)))
        // A method that ChildOfBase declares: the entry of BaseWithReceiver with + matches it too.
        assertEquals(NamedValue("baseId", "b1"), resolver.receiverValue(ChildOfBase(), name(ChildOfBase::class.java)))
        val own = resolver(name(ChildOfBase::class.java) to ReceiverSpec.Excluded(), name(BaseWithReceiver::class.java) + "+" to ReceiverSpec.FieldSpec("baseId"))
        assertNull(own.receiverValue(ChildOfBase(), name(ChildOfBase::class.java)))
        assertEquals(NamedValue("baseId", "b1"), own.receiverValue(ChildOfBase(), name(BaseWithReceiver::class.java)))
        // A declaring class outside the superclass chain, e.g. an interface with a default method: the runtime class,
        // which BaseWithReceiver's entry with + matches.
        assertEquals(NamedValue("baseId", "b1"), resolver.receiverValue(ChildOfBase(), "com.acme.SomeInterface"))
    }

    @Test
    fun `no receiver member means no receiver value`() {
        assertNull(resolver(annotations = reads(here)).receiverValue(Unannotated("c")))
        assertNull(resolver(annotations = reads(here)).receiverValue(Any()))
    }

    @Test
    fun `a missing member is a warning for exact entries only`() {
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))

        assertNull(resolver(here to ReceiverSpec.FieldSpec("nope")).receiverValue(Unannotated("c")))
        assertFalse(err.toString(Charsets.UTF_8).contains("WARN"), err.toString(Charsets.UTF_8))

        assertNull(resolver(name(Unannotated::class.java) to ReceiverSpec.FieldSpec("nope")).receiverValue(Unannotated("c")))
        assertTrue(
            err.toString(Charsets.UTF_8).contains("WARN [augment.receiver] \"${name(Unannotated::class.java)}::*\": no field or property nope found"),
            err.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `a configured member that cannot be accessed is one warning, and its getter is used instead`() {
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        // java.net is not opened to the unnamed module: URI's fields cannot be made accessible.
        val uri = URI("https://example.com")

        assertEquals(NamedValue("scheme", "https"), resolver("java.net.URI" to ReceiverSpec.FieldSpec("scheme")).receiverValue(uri))
        assertFalse(err.toString(Charsets.UTF_8).contains("WARN"), err.toString(Charsets.UTF_8))

        assertNull(resolver("java.net.URI" to ReceiverSpec.FieldSpec("hash")).receiverValue(uri))
        val warnings = err.toString(Charsets.UTF_8).lines().filter { it.contains("WARN") }
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings[0].contains("[augment.receiver] \"java.net.URI::*\": cannot access private transient int java.net.URI.hash"), warnings[0])
    }

    @Test
    fun `a '-' entry ignores the class, without a warning`() {
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val resolver = resolver(name(Annotated::class.java) to ReceiverSpec.Excluded(), annotations = reads(here))
        assertNull(resolver.receiverValue(Annotated()))
        assertEquals(NamedValue("computeKey", "k-2"), resolver.receiverValue(ByMethod()))
        assertFalse(err.toString(Charsets.UTF_8).contains("WARN"), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `a name without a field uses the property's getter`() {
        assertEquals(NamedValue("total", "t-2"), resolver(name(Computed::class.java) to ReceiverSpec.FieldSpec("total")).receiverValue(Computed()))
        assertEquals(NamedValue("isActive", "true"), resolver(name(Computed::class.java) to ReceiverSpec.FieldSpec("isActive")).receiverValue(Computed()))
        assertEquals("getId", ValueResolver.propertyGetter("id"))
        assertEquals("isActive", ValueResolver.propertyGetter("isActive"))
        assertEquals("getIsland", ValueResolver.propertyGetter("island"))
    }

    @Test
    fun `parentheses in receiver values become braces`() {
        val resolver = resolver(name(Point::class.java) to ReceiverSpec.MethodSpec("toString"))
        assertEquals(NamedValue("toString", "Point{x=1}"), resolver.receiverValue(Point(1)))
    }

    @Test
    fun `an array receiver value shows its elements, like an argument`() {
        assertEquals(NamedValue("codes", "[1, 2]"), resolver(annotations = reads(here)).receiverValue(WithArrayReceiver()))
    }

    @Test
    fun `failing receiver member`() {
        assertEquals(NamedValue("id", "?"), resolver(annotations = reads(here)).receiverValue(Throwing()))
    }

    @Test
    fun `parameter values are their text`() {
        val resolver = resolver(name(Unannotated::class.java) to ReceiverSpec.FieldSpec("customerId"), annotations = reads(here))
        assertEquals("null", resolver.paramValue(null))
        assertEquals("42", resolver.paramValue(42))
        assertEquals("RED", resolver.paramValue(Color.RED))
        // Not the receiver value "c" of its entry: toString(), cut at maxValueLength = 10.
        assertEquals("Unannotat…", resolver.paramValue(Unannotated("c")))
        assertEquals("[1, 2, 3]", resolver.paramValue(intArrayOf(1, 2, 3)))
        assertEquals("a b", resolver.paramValue("a\r\nb"))
        // Parentheses, e.g. of a data class's toString(), would confuse IDEs looking for the frame's (File.kt:12).
        assertEquals("Point{x=1}", resolver.paramValue(Point(1)))
        assertEquals("{{a}}", resolver.paramValue("((a))"))
    }

    @Test
    fun `a configured getter is found as a default method of an implemented interface`() {
        val order = JavaDefaults.Order::class.java.name
        assertEquals(NamedValue("getId", "id-7"), resolver(order to ReceiverSpec.MethodSpec("getId")).receiverValue(JavaDefaults.Order()))
        // A property name without a field reads the getter.
        assertEquals(NamedValue("id", "id-7"), resolver(order to ReceiverSpec.FieldSpec("id")).receiverValue(JavaDefaults.Order()))
        // Also through a superclass that implements the interface.
        val rush = JavaDefaults.RushOrder::class.java.name
        assertEquals(NamedValue("getId", "id-7"), resolver(rush to ReceiverSpec.MethodSpec("getId")).receiverValue(JavaDefaults.RushOrder()))
    }
}
