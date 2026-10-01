package com.hafnium.it.buildtime

import com.hafnium.it.buildtime.configured.InventoryAudit
import com.hafnium.it.buildtime.configured.InventoryService
import com.hafnium.it.buildtime.configured.Order
import com.hafnium.it.buildtime.configured.OrderService
import com.hafnium.it.buildtime.configured.Shipment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The fixtures in src/main were instrumented at build time; the tests run without a Java agent. */
class BuildTimeInstrumentationTest {

    @Test
    fun `annotated classes show their receiver and parameter ids`() {
        val e = assertThrows<Exception> { ClassWithoutAnnotation("object-2").method("123") }
        assertEquals(
            listOf(
                "com.hafnium.it.buildtime.ClassWithAnnotation{objectId=object-1}.error",
                "com.hafnium.it.buildtime.ClassWithAnnotation{objectId=object-1}.method{param=ObjectParam{name=object-param-1}, token=#fb07916a}",
                "com.hafnium.it.buildtime.ClassWithAnnotation{objectId=object-1}.transfer{from=a, to=b, amount=10}",
                // The exact entry beats the "@" pattern.
                "com.hafnium.it.buildtime.ClassWithoutAnnotation.method{q=123}",
            ),
            frames(e, 4),
        )
    }

    @Test
    fun `classes without annotations take their ids from the configuration`() {
        val e = assertThrows<IllegalStateException> { OrderService(InventoryService(InventoryAudit())).process(Order(4711), 3, "rush") }
        assertEquals(
            listOf(
                "com.hafnium.it.buildtime.configured.Customer{customerId=c-9}.notify{email=#71d4f55f}",
                "com.hafnium.it.buildtime.configured.Order{getOrderNumber=4711}.ship",
                // Its "-" entry beats the Inventory* pattern.
                "com.hafnium.it.buildtime.configured.InventoryAudit.record",
                "com.hafnium.it.buildtime.configured.InventoryService.reserve{sku=x-1, count=3}",
                "com.hafnium.it.buildtime.configured.OrderService.process{order=Order#4711, quantity=3}",
            ),
            frames(e, 5),
        )
    }

    @Test
    fun `constructors show their parameter ids`() {
        val e = assertThrows<IllegalArgumentException> { Shipment(4711, 0.0, "fragile") }
        assertEquals(listOf("com.hafnium.it.buildtime.configured.Shipment.<init>{orderId=4711, weight=0.0}"), frames(e, 1))
    }

    @Test
    fun `default method shows its interface's receiver id`() {
        val e = assertThrows<IllegalStateException> { Parcel("p-1").track() }
        assertEquals("com.hafnium.it.buildtime.Tracked{trackingNumber=T-p-1}", e.stackTrace[0].className)
        assertEquals("track", e.stackTrace[0].methodName)
        assertEquals("com.hafnium.it.buildtime.Parcel", e.stackTrace[1].className)
        assertEquals("track", e.stackTrace[1].methodName)
    }

    private fun frames(e: Throwable, count: Int) = e.stackTrace.take(count).map { "${it.className}.${it.methodName}" }
}
