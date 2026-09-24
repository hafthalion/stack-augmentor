package com.hafnium.stackaugmentor.agent

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

    private fun resolver(vararg ids: Pair<String, IdSpec>, fallback: Fallback = Fallback.TO_STRING) =
        IdResolver(AugmentorConfig(ids = ids.toMap(), fallback = fallback, maxIdLength = 10))

    @Test
    fun `annotated field, method and constructor property`() {
        assertEquals(NamedId("objectId", "a-1"), resolver().receiverId(Annotated(), allowFallback = false))
        assertEquals(NamedId("key", "k-2"), resolver().receiverId(ByMethod(), allowFallback = false))
        assertEquals(NamedId("code", "X"), resolver().receiverId(ConstructorProperty("X"), allowFallback = false))
    }

    @Test
    fun `configuration wins over annotations`() {
        val resolver = resolver(Annotated::class.java.name to IdSpec.FieldSpec("customerId"))
        assertEquals(NamedId("customerId", "c-1"), resolver.receiverId(Annotated(), allowFallback = false))
    }

    @Test
    fun `configured method`() {
        val resolver = resolver(Unannotated::class.java.name to IdSpec.MethodSpec("toString"))
        assertEquals(NamedId("toString", "Unannotat…"), resolver.receiverId(Unannotated("c-7"), allowFallback = false))
    }

    @Test
    fun fallbacks() {
        val plain = Any()
        assertNull(resolver().receiverId(Unannotated("c"), allowFallback = false))
        assertEquals(NamedId("toString", "Unannotat…"), resolver().receiverId(Unannotated("c"), allowFallback = true))
        assertEquals(NamedId("identity", Integer.toHexString(System.identityHashCode(plain))), resolver().receiverId(plain, allowFallback = true))
        assertEquals("identity", resolver(fallback = Fallback.IDENTITY).receiverId(Unannotated("c"), allowFallback = true)?.name)
        assertNull(resolver(fallback = Fallback.NONE).receiverId(Unannotated("c"), allowFallback = true))
    }

    @Test
    fun `failing id source`() {
        assertEquals(NamedId("id", "?"), resolver().receiverId(Throwing(), allowFallback = false))
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
