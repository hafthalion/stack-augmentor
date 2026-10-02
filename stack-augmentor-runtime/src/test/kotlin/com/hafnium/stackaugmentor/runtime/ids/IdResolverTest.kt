package com.hafnium.stackaugmentor.runtime.ids

import com.hafnium.stackaugmentor.StackTraceId
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig
import com.hafnium.stackaugmentor.runtime.config.IdSpec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdResolverTest {

    class Annotated {
        @StackTraceId
        val objectId = "a-1"

        @Suppress("unused")
        private val customerId = "c-1"
    }

    class ByMethod {
        @StackTraceId
        fun computeKey() = "k-${1 + 1}"
    }

    class ConstructorProperty(@StackTraceId val code: String)

    class Unannotated(private val customerId: String) {
        override fun toString() = "Unannotated($customerId)"
    }

    class Throwing {
        @StackTraceId
        fun id(): String = throw IllegalStateException("no id")
    }

    open class BaseWithId {
        @StackTraceId
        val baseId = "b1"
    }

    class ChildOfBase : BaseWithId()

    open class MultiAnnotated {
        @StackTraceId
        val tenant = "acme"

        @StackTraceId
        val orderId = 42

        @StackTraceId
        fun region() = "eu"

        @StackTraceId
        fun channel() = "web"
    }

    class ChildOfMulti : MultiAnnotated() {
        @StackTraceId
        val lineId = 7
    }

    enum class Color { RED }

    data class Point(val x: Int)

    /** Kotlin properties without a backing field: only their getters exist. */
    class Computed {
        val total get() = "t-${1 + 1}"
        val isActive get() = true
    }

    class WithArrayId {
        @StackTraceId
        val codes = intArrayOf(1, 2)
    }

    /** All classes of this test are in this package. */
    private val here = IdResolverTest::class.java.packageName + ".**"
    private val annotations = IdSpec.Annotations()

    private val originalErr = System.err

    @AfterEach
    fun restore() = System.setErr(originalErr)

    private fun resolver(vararg classes: Pair<String, IdSpec>) =
        IdResolver(AugmentorConfig.builder().classes(classes.toMap()).maxIdLength(10).build())

    private fun name(type: Class<*>) = type.name

    /** The only receiver id, or null. */
    private fun IdResolver.receiverId(target: Any, declaringClass: String = target.javaClass.name): NamedId? =
        receiverIds(target, declaringClass).also { assertTrue(it.size <= 1, "$it") }.singleOrNull()

    @Test
    fun `every annotated member gives an id, fields first, then methods by name, nearest class first`() {
        val resolver = resolver(here to annotations)
        assertEquals(
            listOf(NamedId("tenant", "acme"), NamedId("orderId", "42"), NamedId("channel", "web"), NamedId("region", "eu")),
            resolver.receiverIds(MultiAnnotated()),
        )
        assertEquals(listOf("lineId", "tenant", "orderId", "channel", "region"), resolver.receiverIds(ChildOfMulti()).map { it.name() })
    }

    @Test
    fun `a list of members gives one id each, in the configured order`() {
        val list = IdSpec.MemberList(listOf(IdSpec.FieldSpec("customerId"), IdSpec.MethodSpec("toString")))
        assertEquals(
            listOf(NamedId("customerId", "c-7"), NamedId("toString", "Unannotat…")),
            resolver(name(Unannotated::class.java) to list).receiverIds(Unannotated("c-7")),
        )
    }

    @Test
    fun `a missing member of a list is reported and left out`() {
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val list = IdSpec.MemberList(listOf(IdSpec.FieldSpec("nope"), IdSpec.FieldSpec("customerId")))
        assertEquals(listOf(NamedId("customerId", "c-1")), resolver(name(Annotated::class.java) to list).receiverIds(Annotated()))
        assertTrue(err.toString().contains("no field or property nope found"), err.toString())
    }

    @Test
    fun `annotated field, method and constructor property`() {
        val resolver = resolver(here to annotations)
        assertEquals(NamedId("objectId", "a-1"), resolver.receiverId(Annotated()))
        assertEquals(NamedId("computeKey", "k-2"), resolver.receiverId(ByMethod()))
        assertEquals(NamedId("code", "X"), resolver.receiverId(ConstructorProperty("X")))
    }

    @Test
    fun `annotations are only used with an '@' entry`() {
        assertNull(resolver().receiverId(Annotated()))
        assertNull(resolver("com.acme.**" to annotations).receiverId(Annotated()))
        assertEquals(NamedId("objectId", "a-1"), resolver(name(Annotated::class.java) to annotations).receiverId(Annotated()))
    }

    @Test
    fun `the most specific entry wins`() {
        // An exact explicit entry beats an "@" pattern.
        val exact = resolver(here to annotations, name(Annotated::class.java) to IdSpec.FieldSpec("customerId"))
        assertEquals(NamedId("customerId", "c-1"), exact.receiverId(Annotated()))
        // A longer "@" pattern beats a shorter explicit one.
        val longer = resolver(here to IdSpec.FieldSpec("customerId"), name(Annotated::class.java).dropLast(3) + "*" to annotations)
        assertEquals(NamedId("objectId", "a-1"), longer.receiverId(Annotated()))
    }

    @Test
    fun `configured method`() {
        val resolver = resolver(name(Unannotated::class.java) to IdSpec.MethodSpec("toString"))
        assertEquals(NamedId("toString", "Unannotat…"), resolver.receiverId(Unannotated("c-7")))
    }

    @Test
    fun `an entry does not apply to subclasses, but members are found in superclasses`() {
        assertNull(resolver(name(BaseWithId::class.java) to annotations).receiverId(ChildOfBase()))
        assertNull(resolver(name(BaseWithId::class.java) to IdSpec.FieldSpec("baseId")).receiverId(ChildOfBase()))
        // An entry of the subclass itself finds the annotation or the field that the superclass declares.
        assertEquals(NamedId("baseId", "b1"), resolver(name(ChildOfBase::class.java) to annotations).receiverId(ChildOfBase()))
        assertEquals(NamedId("baseId", "b1"), resolver(name(ChildOfBase::class.java) to IdSpec.FieldSpec("baseId")).receiverId(ChildOfBase()))
    }

    @Test
    fun `the class declaring the method decides, read from the object`() {
        val resolver = resolver(name(BaseWithId::class.java) to IdSpec.FieldSpec("baseId"))
        // A method that BaseWithId declares, running on a ChildOfBase: the entry of BaseWithId applies.
        assertEquals(NamedId("baseId", "b1"), resolver.receiverId(ChildOfBase(), name(BaseWithId::class.java)))
        // A method that ChildOfBase declares: it has no entry.
        assertNull(resolver.receiverId(ChildOfBase(), name(ChildOfBase::class.java)))
        // A declaring class outside the superclass chain, e.g. an interface with a default method: the runtime class.
        assertNull(resolver.receiverId(ChildOfBase(), "com.acme.SomeInterface"))
    }

    @Test
    fun `no id source means no receiver id`() {
        assertNull(resolver(here to annotations).receiverId(Unannotated("c")))
        assertNull(resolver(here to annotations).receiverId(Any()))
    }

    @Test
    fun `a missing member is a warning for exact entries only`() {
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))

        assertNull(resolver(here to IdSpec.FieldSpec("nope")).receiverId(Unannotated("c")))
        assertFalse(err.toString(Charsets.UTF_8).contains("WARN"), err.toString(Charsets.UTF_8))

        assertNull(resolver(name(Unannotated::class.java) to IdSpec.FieldSpec("nope")).receiverId(Unannotated("c")))
        assertTrue(
            err.toString(Charsets.UTF_8).contains("WARN [augment.receiver] \"${name(Unannotated::class.java)}\": no field or property nope found"),
            err.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `a configured member that cannot be accessed is one warning, and its getter is used instead`() {
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        // java.net is not opened to the unnamed module: URI's fields cannot be made accessible.
        val uri = URI("https://example.com")

        assertEquals(NamedId("scheme", "https"), resolver("java.net.URI" to IdSpec.FieldSpec("scheme")).receiverId(uri))
        assertFalse(err.toString(Charsets.UTF_8).contains("WARN"), err.toString(Charsets.UTF_8))

        assertNull(resolver("java.net.URI" to IdSpec.FieldSpec("hash")).receiverId(uri))
        val warnings = err.toString(Charsets.UTF_8).lines().filter { it.contains("WARN") }
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings[0].contains("[augment.receiver] \"java.net.URI\": cannot access private transient int java.net.URI.hash"), warnings[0])
    }

    @Test
    fun `annotations need an entry`() {
        assertNull(resolver().receiverId(Annotated()))
        // An entry that is not "@" ignores the annotations.
        val configured = resolver(name(Annotated::class.java) to IdSpec.FieldSpec("customerId"))
        assertEquals(NamedId("customerId", "c-1"), configured.receiverId(Annotated()))
    }

    @Test
    fun `a '-' entry ignores the class, without a warning`() {
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val resolver = resolver(here to annotations, name(Annotated::class.java) to IdSpec.Excluded())
        assertNull(resolver.receiverId(Annotated()))
        assertEquals(NamedId("computeKey", "k-2"), resolver.receiverId(ByMethod()))
        assertFalse(err.toString(Charsets.UTF_8).contains("WARN"), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `a name without a field uses the property's getter`() {
        assertEquals(NamedId("total", "t-2"), resolver(name(Computed::class.java) to IdSpec.FieldSpec("total")).receiverId(Computed()))
        assertEquals(NamedId("isActive", "true"), resolver(name(Computed::class.java) to IdSpec.FieldSpec("isActive")).receiverId(Computed()))
        assertEquals("getId", IdResolver.propertyGetter("id"))
        assertEquals("isActive", IdResolver.propertyGetter("isActive"))
        assertEquals("getIsland", IdResolver.propertyGetter("island"))
    }

    @Test
    fun `parentheses in receiver ids become braces`() {
        val resolver = resolver(name(Point::class.java) to IdSpec.MethodSpec("toString"))
        assertEquals(NamedId("toString", "Point{x=1}"), resolver.receiverId(Point(1)))
    }

    @Test
    fun `an array receiver id shows its elements, like an argument`() {
        assertEquals(NamedId("codes", "[1, 2]"), resolver(here to annotations).receiverId(WithArrayId()))
    }

    @Test
    fun `failing id source`() {
        assertEquals(NamedId("id", "?"), resolver(here to annotations).receiverId(Throwing()))
    }

    @Test
    fun `parameter values are their text`() {
        val resolver = resolver(here to annotations, name(Unannotated::class.java) to IdSpec.FieldSpec("customerId"))
        assertEquals("null", resolver.paramId(null))
        assertEquals("42", resolver.paramId(42))
        assertEquals("RED", resolver.paramId(Color.RED))
        // Not the receiver id "c" of its entry: toString(), cut at maxIdLength = 10.
        assertEquals("Unannotat…", resolver.paramId(Unannotated("c")))
        assertEquals("[1, 2, 3]", resolver.paramId(intArrayOf(1, 2, 3)))
        assertEquals("a b", resolver.paramId("a\r\nb"))
        // Parentheses, e.g. of a data class's toString(), would confuse IDEs looking for the frame's (File.kt:12).
        assertEquals("Point{x=1}", resolver.paramId(Point(1)))
        assertEquals("{{a}}", resolver.paramId("((a))"))
    }

    @Test
    fun `a configured getter is found as a default method of an implemented interface`() {
        val order = JavaDefaults.Order::class.java.name
        assertEquals(NamedId("getId", "id-7"), resolver(order to IdSpec.MethodSpec("getId")).receiverId(JavaDefaults.Order()))
        // A property name without a field reads the getter.
        assertEquals(NamedId("id", "id-7"), resolver(order to IdSpec.FieldSpec("id")).receiverId(JavaDefaults.Order()))
        // Also through a superclass that implements the interface.
        val rush = JavaDefaults.RushOrder::class.java.name
        assertEquals(NamedId("getId", "id-7"), resolver(rush to IdSpec.MethodSpec("getId")).receiverId(JavaDefaults.RushOrder()))
    }
}
