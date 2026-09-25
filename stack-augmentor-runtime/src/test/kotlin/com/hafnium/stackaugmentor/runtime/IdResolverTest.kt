package com.hafnium.stackaugmentor.runtime

import com.hafnium.stackaugmentor.StackTraceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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

    enum class Color { RED }

    private fun resolver(vararg ids: Pair<String, IdSpec>, annotated: List<String> = emptyList()) =
        IdResolver(AugmentorConfig(ids = ids.toMap(), augmentAnnotatedClasses = annotated, maxIdLength = 10))

    @Test
    fun `annotated field, method and constructor property`() {
        assertEquals(NamedId("objectId", "a-1"), resolver().receiverId(Annotated()))
        assertEquals(NamedId("key", "k-2"), resolver().receiverId(ByMethod()))
        assertEquals(NamedId("code", "X"), resolver().receiverId(ConstructorProperty("X")))
    }

    @Test
    fun `configuration wins over annotations`() {
        val resolver = resolver(Annotated::class.java.name to IdSpec.FieldSpec("customerId"))
        assertEquals(NamedId("customerId", "c-1"), resolver.receiverId(Annotated()))
    }

    @Test
    fun `configured method`() {
        val resolver = resolver(Unannotated::class.java.name to IdSpec.MethodSpec("toString"))
        assertEquals(NamedId("toString", "Unannotat…"), resolver.receiverId(Unannotated("c-7")))
    }

    @Test
    fun `no id source means no receiver id`() {
        assertNull(resolver().receiverId(Unannotated("c")))
        assertNull(resolver().receiverId(Any()))
    }

    @Test
    fun `annotations outside augmentAnnotatedClasses are ignored`() {
        assertNull(resolver(annotated = listOf("com.acme.**")).receiverId(Annotated()))
        assertEquals(NamedId("objectId", "a-1"), resolver(annotated = listOf("com.hafnium.**")).receiverId(Annotated()))
        // Configured ids apply in every package.
        val configured = resolver(Annotated::class.java.name to IdSpec.FieldSpec("customerId"), annotated = listOf("com.acme.**"))
        assertEquals(NamedId("customerId", "c-1"), configured.receiverId(Annotated()))
    }

    @Test
    fun `failing id source`() {
        assertEquals(NamedId("id", "?"), resolver().receiverId(Throwing()))
    }

    @Test
    fun `parameter values`() {
        val resolver = resolver()
        assertEquals("null", resolver.paramId(null))
        assertEquals("42", resolver.paramId(42))
        assertEquals("RED", resolver.paramId(Color.RED))
        assertEquals("a-1", resolver.paramId(Annotated()))
        assertEquals("[1, 2, 3]", resolver.paramId(intArrayOf(1, 2, 3)))
        assertEquals("a b", resolver.paramId("a\r\nb"))
    }
}
