package com.hafnium.stackaugmentor.runtime

import com.hafnium.stackaugmentor.StackTraceId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class IdResolverTest {

    class Annotated {
        @StackTraceId
        val objectId = "a-1"

        @Suppress("unused")
        private val customerId = "c-1"
    }

    class ByMethod {
        @StackTraceId(name = "key")
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

    enum class Color { RED }

    /** All classes of this test are in this package. */
    private val here = IdResolverTest::class.java.packageName + ".**"
    private val annotations = IdSpec.Annotations()

    private val originalErr = System.err

    @AfterEach
    fun restore() = System.setErr(originalErr)

    private fun resolver(vararg classes: Pair<String, IdSpec>) =
        IdResolver(AugmentorConfig.builder().classes(classes.toMap()).maxIdLength(10).build())

    private fun name(type: Class<*>) = type.name

    @Test
    fun `annotated field, method and constructor property`() {
        val resolver = resolver(here to annotations)
        assertEquals(NamedId("objectId", "a-1"), resolver.receiverId(Annotated()))
        assertEquals(NamedId("key", "k-2"), resolver.receiverId(ByMethod()))
        assertEquals(NamedId("code", "X"), resolver.receiverId(ConstructorProperty("X")))
    }

    @Test
    fun `annotations are only used with an "@" entry`() {
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
            err.toString(Charsets.UTF_8).contains("WARN [augment.receiver] \"${name(Unannotated::class.java)}\": no field nope found"),
            err.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `annotations need an entry`() {
        assertNull(resolver().receiverId(Annotated()))
        assertNotEquals("a-1", resolver().paramId(Annotated()))
        // An entry that is not "@" ignores the annotations.
        val configured = resolver(name(Annotated::class.java) to IdSpec.FieldSpec("customerId"))
        assertEquals(NamedId("customerId", "c-1"), configured.receiverId(Annotated()))
    }

    @Test
    fun `a "-" entry ignores the class, without a warning`() {
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val resolver = resolver(here to annotations, name(Annotated::class.java) to IdSpec.Excluded())
        assertNull(resolver.receiverId(Annotated()))
        assertEquals(NamedId("key", "k-2"), resolver.receiverId(ByMethod()))
        assertFalse(err.toString(Charsets.UTF_8).contains("WARN"), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `failing id source`() {
        assertEquals(NamedId("id", "?"), resolver(here to annotations).receiverId(Throwing()))
    }

    @Test
    fun `parameter values`() {
        val resolver = resolver(here to annotations)
        assertEquals("null", resolver.paramId(null))
        assertEquals("42", resolver.paramId(42))
        assertEquals("RED", resolver.paramId(Color.RED))
        assertEquals("a-1", resolver.paramId(Annotated()))
        assertEquals("[1, 2, 3]", resolver.paramId(intArrayOf(1, 2, 3)))
        assertEquals("a b", resolver.paramId("a\r\nb"))
    }
}
