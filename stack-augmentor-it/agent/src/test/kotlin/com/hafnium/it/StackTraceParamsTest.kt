package com.hafnium.it

import com.hafnium.it.fixtures.Accounts
import com.hafnium.it.fixtures.Crate
import com.hafnium.it.fixtures.DerivedInventory
import com.hafnium.it.fixtures.ExpressParcel
import com.hafnium.it.fixtures.Inventory
import com.hafnium.it.fixtures.Overlap
import com.hafnium.it.fixtures.Pallet
import com.hafnium.it.fixtures.Secrets
import com.hafnium.it.fixtures.Shipment
import com.hafnium.it.report.FrameReport
import com.thirdparty.InventoryService
import com.thirdparty.UserService
import com.thirdparty.db.OrderRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * @AugmentedParams and wildcard [augment.params] entries; runs with the agent, see build.gradle.kts. [FrameReport]
 * writes the frames each test caught to build/reports/frames/StackTraceParamsTest.html.
 */
class StackTraceParamsTest {

    private fun Throwable.method(index: Int = 0): String = stackTrace[index].methodName

    @Test
    fun `method-level annotation shows all parameters`() {
        assertEquals("transfer{from=a, to=b, amount=10}", report.thrown<IllegalStateException, _>("Accounts().transfer(\"a\", \"b\", 10)", Accounts()) { it.transfer("a", "b", 10) }.method())
        assertEquals("plain", report.thrown<IllegalStateException, _>("Accounts().plain(\"a\")", Accounts()) { it.plain("a") }.method())
    }

    @Test
    fun `a parameter annotation under a method-level annotation selects nothing more`() {
        assertEquals("move{item=x-1, count=2}", report.thrown<IllegalStateException, _>("Accounts().move(\"x-1\", 2)", Accounts()) { it.move("x-1", 2) }.method())
    }

    @Test
    fun `a method-level annotation does not apply to overrides`() {
        val reserve = report.thrown<IllegalStateException, _>("Inventory().reserve(\"x-1\", 2)", Inventory()) { it.reserve("x-1", 2) }
        assertEquals("com.hafnium.it.fixtures.Inventory", reserve.stackTrace[0].className)
        assertEquals("reserve{sku=x-1, count=2}", reserve.method())
        assertEquals("release{sku=x-2}", report.thrown<IllegalStateException, _>("Inventory().release(\"x-2\")", Inventory()) { it.release("x-2") }.method())

        val derived = report.thrown<IllegalStateException, _>("DerivedInventory().reserve(\"x-3\", 1)", DerivedInventory()) { it.reserve("x-3", 1) }
        assertEquals("com.hafnium.it.fixtures.DerivedInventory", derived.stackTrace[0].className)
        assertEquals("reserve", derived.method())
    }

    @Test
    fun `a parameter selected twice is shown once`() {
        assertEquals("op{x=1, y=2}", report.thrown<IllegalStateException, _>("Overlap().op(1, 2)", Overlap()) { it.op(1, 2) }.method())
    }

    @Test
    fun `third-party classes selected by wildcards`() {
        val reserve = report.thrown<IllegalStateException, _>("InventoryService().reserve(\"x-1\", 2)", InventoryService()) { it.reserve("x-1", 2) }
        assertEquals("com.thirdparty.InventoryService", reserve.stackTrace[0].className)
        assertEquals("reserve{sku=x-1, count=2}", reserve.method())

        assertEquals("findById{id=7}", report.thrown<IllegalStateException, _>("OrderRepository().findById(7)", OrderRepository()) { it.findById(7) }.method())
        assertEquals("findAll", report.thrown<IllegalStateException, _>("OrderRepository().findAll()", OrderRepository()) { it.findAll() }.method())
    }

    @Test
    fun `hashed parameters show the start of the SHA-256 of their text`() {
        assertEquals(
            "invite{user=ann, email=#71d4f55f, attempt=#4e074085}",
            report.thrown<IllegalStateException, _>("UserService().invite(\"ann\", \"ann@example.com\", 3)", UserService()) { it.invite("ann", "ann@example.com", 3) }.method(),
        )
        assertEquals(
            "invite{user=ann, email=#71d4f55f}",
            report.thrown<IllegalStateException, _>("Secrets().invite(\"ann\", \"ann@example.com\")", Secrets()) { it.invite("ann", "ann@example.com") }.method(),
        )
        assertEquals(
            "register{email=#d648b243, nickname=annie}",
            report.thrown<IllegalStateException, _>("Secrets().register(\"a@b.c\", \"annie\")", Secrets()) { it.register("a@b.c", "annie") }.method(),
        )
    }

    @Test
    fun `constructors show their parameter values, never a receiver member`() {
        val shipment = report.thrown<IllegalArgumentException>("Shipment(42, \"ann@example.com\", 0)") { Shipment(42, "ann@example.com", 0) }
        assertEquals("com.hafnium.it.fixtures.Shipment", shipment.stackTrace[0].className)
        assertEquals("<init>{orderId=42, email=#71d4f55f}", shipment.method())
    }

    @Test
    fun `an exception from the constructor called with this augments the delegating constructor only from the live stack`() {
        val pallet = report.thrown<IllegalStateException>("Pallet(\"big\", 200L)") { Pallet("big", 200L) }
        assertEquals("<init>{size=200}", pallet.method(0))
        assertEquals(if (AgentMode.liveStack) "<init>{label=big, size=200}" else "<init>", pallet.method(1))
    }

    @Test
    fun `a delegating constructor shows its parameter values for exceptions from its own body`() {
        val pallet = report.thrown<IllegalArgumentException>("Pallet(\"\", 1L)") { Pallet("", 1L) }
        assertEquals("<init>{label=, size=1}", pallet.method(0))
    }

    @Test
    fun `an exception while computing the arguments of super is augmented`() {
        val crate = report.thrown<IllegalArgumentException>("Crate(null, 3)") { Crate(null, 3) }
        assertEquals("com.hafnium.it.fixtures.Crate", crate.stackTrace[0].className)
        assertEquals("<init>{code=null, size=3}", crate.method())
    }

    @Test
    fun `an exception from the superclass constructor augments the subclass constructor only from the live stack`() {
        val parcel = report.thrown<IllegalArgumentException>("ExpressParcel(\"\", 1)") { ExpressParcel("", 1) }
        assertEquals("com.hafnium.it.fixtures.Parcel", parcel.stackTrace[0].className)
        assertEquals("<init>", parcel.method(0))
        assertEquals("com.hafnium.it.fixtures.ExpressParcel", parcel.stackTrace[1].className)
        assertEquals(if (AgentMode.liveStack) "<init>{code=, priority=1}" else "<init>", parcel.method(1))
    }

    @Test
    fun `JDK classes stay uninstrumented even when a wildcard matches them`() {
        val e = report.thrown<NumberFormatException>("Integer.parseInt(\"x\")") { Integer.parseInt("x") }
        e.stackTrace.filter { it.className.startsWith("java.") }.forEach { frame ->
            assertEquals(false, frame.className.contains('{') || frame.methodName.contains('{'), frame.toString())
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val report = FrameReport("Parameter values", basePackage = "com.hafnium.it.fixtures", ownPackage = "com.hafnium.it")
    }
}
