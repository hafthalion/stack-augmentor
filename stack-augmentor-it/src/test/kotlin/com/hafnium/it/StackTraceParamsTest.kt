package com.hafnium.it

import com.hafnium.it.fixtures.Accounts
import com.hafnium.it.fixtures.DerivedInventory
import com.hafnium.it.fixtures.Inventory
import com.hafnium.it.fixtures.Overlap
import com.hafnium.it.fixtures.Secrets
import com.hafnium.it.fixtures.Wide
import com.hafnium.it.outside.AllParamsOutside
import com.thirdparty.InventoryService
import com.thirdparty.UserService
import com.thirdparty.db.OrderRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** @StackTraceParams and wildcard [augment.params] entries; runs with the agent, see build.gradle.kts. */
class StackTraceParamsTest {

    private fun Throwable.method(index: Int = 0): String = stackTrace[index].methodName

    @Test
    fun `method-level annotation shows all parameters`() {
        assertEquals("transfer{from=a, to=b, amount=10}", assertThrows<IllegalStateException> { Accounts().transfer("a", "b", 10) }.method())
        assertEquals("plain", assertThrows<IllegalStateException> { Accounts().plain("a") }.method())
    }

    @Test
    fun `a parameter annotation under a method-level annotation selects nothing more`() {
        assertEquals("move{item=x-1, count=2}", assertThrows<IllegalStateException> { Accounts().move("x-1", 2) }.method())
    }

    @Test
    fun `a method-level annotation does not apply to overrides`() {
        val reserve = assertThrows<IllegalStateException> { Inventory().reserve("x-1", 2) }
        assertEquals("com.hafnium.it.fixtures.Inventory", reserve.stackTrace[0].className)
        assertEquals("reserve{sku=x-1, count=2}", reserve.method())
        assertEquals("release{sku=x-2}", assertThrows<IllegalStateException> { Inventory().release("x-2") }.method())

        val derived = assertThrows<IllegalStateException> { DerivedInventory().reserve("x-3", 1) }
        assertEquals("com.hafnium.it.fixtures.DerivedInventory", derived.stackTrace[0].className)
        assertEquals("reserve", derived.method())
    }

    @Test
    fun `an annotation outside the @ entries is ignored`() {
        assertEquals("run", assertThrows<IllegalStateException> { AllParamsOutside().run(1) }.method())
    }

    @Test
    fun `a parameter selected twice is shown once`() {
        assertEquals("op{x=1, y=2}", assertThrows<IllegalStateException> { Overlap().op(1, 2) }.method())
    }

    @Test
    fun `all selected parameters are shown`() {
        val e = assertThrows<IllegalStateException> { Wide().ten(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) }
        assertEquals("ten{a=1, b=2, c=3, d=4, e=5, f=6, g=7, h=8, i=9, j=10}", e.method())
    }

    @Test
    fun `third-party classes selected by wildcards`() {
        val reserve = assertThrows<IllegalStateException> { InventoryService().reserve("x-1", 2) }
        assertEquals("com.thirdparty.InventoryService", reserve.stackTrace[0].className)
        assertEquals("reserve{sku=x-1, count=2}", reserve.method())

        assertEquals("findById{id=7}", assertThrows<IllegalStateException> { OrderRepository().findById(7) }.method())
        assertEquals("findAll", assertThrows<IllegalStateException> { OrderRepository().findAll() }.method())
    }

    @Test
    fun `hashed parameters show the start of the SHA-256 of their text`() {
        assertEquals(
            "invite{user=ann, email=#71d4f55f, attempt=#4e074085}",
            assertThrows<IllegalStateException> { UserService().invite("ann", "ann@example.com", 3) }.method(),
        )
        assertEquals(
            "invite{user=ann, email=#71d4f55f}",
            assertThrows<IllegalStateException> { Secrets().invite("ann", "ann@example.com") }.method(),
        )
        assertEquals(
            "register{email=#d648b243, nickname=annie}",
            assertThrows<IllegalStateException> { Secrets().register("a@b.c", "annie") }.method(),
        )
    }

    @Test
    fun `JDK classes stay uninstrumented even when a wildcard matches them`() {
        val e = assertThrows<NumberFormatException> { Integer.parseInt("x") }
        e.stackTrace.filter { it.className.startsWith("java.") }.forEach { frame ->
            assertEquals(false, frame.className.contains('{') || frame.methodName.contains('{'), frame.toString())
        }
    }
}
