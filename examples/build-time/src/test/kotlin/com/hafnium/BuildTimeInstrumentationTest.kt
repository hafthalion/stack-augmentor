package com.hafnium

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The main classes were instrumented at build time; the tests run without a Java agent. */
class BuildTimeInstrumentationTest {

    @Test
    fun `annotated class shows its receiver and parameter ids`() {
        val e = assertThrows<Exception> { ClassWithAnnotation("object-1").method(ObjectParam("object-param-1")) }
        assertEquals("com.hafnium.ClassWithAnnotation{objectId=object-1}", e.stackTrace[0].className)
        assertEquals("error", e.stackTrace[0].methodName)
        assertEquals("com.hafnium.ClassWithAnnotation{objectId=object-1}", e.stackTrace[1].className)
        assertEquals("method{param=ObjectParam{name=object-param-1}}", e.stackTrace[1].methodName)
    }

    @Test
    fun `class without annotations is unchanged`() {
        val e = assertThrows<Exception> { ClassWithoutAnnotation("object-2").method() }
        assertEquals("com.hafnium.ClassWithoutAnnotation", e.stackTrace[0].className)
        assertEquals("method", e.stackTrace[0].methodName)
    }

    @Test
    fun `class-level StackTraceParams shows all parameters`() {
        val e = assertThrows<IllegalStateException> { Shipping().ship("o-17", 2) }
        assertEquals("com.hafnium.Shipping", e.stackTrace[0].className)
        assertEquals("ship{orderId=o-17, quantity=2}", e.stackTrace[0].methodName)
    }
}
