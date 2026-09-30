package com.hafnium.examples.buildtime

import com.thirdparty.InventoryAudit
import com.thirdparty.InventoryService
import com.thirdparty.Order
import com.thirdparty.OrderService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The main classes were instrumented at build time; the tests run without a Java agent. */
class BuildTimeInstrumentationTest {

    @Test
    fun `annotated classes show their receiver and parameter ids`() {
        val e = assertThrows<Exception> { ClassWithoutAnnotation("object-2").method("123") }
        assertEquals(
            listOf(
                "com.hafnium.examples.buildtime.ClassWithAnnotation{objectId=object-1}.error",
                "com.hafnium.examples.buildtime.ClassWithAnnotation{objectId=object-1}.method{param=ObjectParam{name=object-param-1}, token=#fb07916a}",
                "com.hafnium.examples.buildtime.ClassWithAnnotation{objectId=object-1}.transfer{from=a, to=b, amount=10}",
                // The exact entry beats the "@" pattern.
                "com.hafnium.examples.buildtime.ClassWithoutAnnotation.method{q=123}",
            ),
            frames(e, 4),
        )
    }

    @Test
    fun `classes without annotations take their ids from the configuration`() {
        val e = assertThrows<IllegalStateException> { OrderService(InventoryService(InventoryAudit())).process(Order(4711), 3, "rush") }
        assertEquals(
            listOf(
                "com.thirdparty.Customer{customerId=c-9}.notify{email=#71d4f55f}",
                "com.thirdparty.Order{getOrderNumber=4711}.ship",
                // Its "-" entry beats the Inventory* pattern.
                "com.thirdparty.InventoryAudit.record",
                "com.thirdparty.InventoryService.reserve{sku=x-1, count=3}",
                "com.thirdparty.OrderService.process{order=Order#4711, quantity=3}",
            ),
            frames(e, 5),
        )
    }

    @Test
    fun `default method shows its interface's receiver id`() {
        val e = assertThrows<IllegalStateException> { Parcel("p-1").track() }
        assertEquals("com.hafnium.examples.buildtime.Tracked{trackingNumber=T-p-1}", e.stackTrace[0].className)
        assertEquals("track", e.stackTrace[0].methodName)
        assertEquals("com.hafnium.examples.buildtime.Parcel", e.stackTrace[1].className)
        assertEquals("track", e.stackTrace[1].methodName)
    }

    private fun frames(e: Throwable, count: Int) = e.stackTrace.take(count).map { "${it.className}.${it.methodName}" }
}
