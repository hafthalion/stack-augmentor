package com.hafnium.stackaugmentor.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WeakIdentityMapTest {

    /** Equal by value, like some exception classes that override equals. */
    private class EqualException(message: String) : RuntimeException(message) {
        override fun equals(other: Any?) = other is EqualException && other.message == message
        override fun hashCode() = message.hashCode()
    }

    @Test
    fun `keys are compared by identity`() {
        val map = WeakIdentityMap<Throwable, Int>()
        val first = EqualException("same")
        val second = EqualException("same")
        map[first] = 1
        assertEquals(1, map[first])
        assertNull(map[second])
        map[second] = 2
        assertEquals(1, map[first])
        assertEquals(2, map[second])
    }

    @Test
    fun `put replaces the value`() {
        val map = WeakIdentityMap<Throwable, Int>()
        val key = RuntimeException()
        map[key] = 1
        map[key] = 2
        assertEquals(2, map[key])
    }
}
