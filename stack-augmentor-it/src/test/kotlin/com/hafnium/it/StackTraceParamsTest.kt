package com.hafnium.it

import com.hafnium.it.fixtures.Accounts
import com.hafnium.it.fixtures.Crate
import com.hafnium.it.fixtures.DerivedInventory
import com.hafnium.it.fixtures.ExpressParcel
import com.hafnium.it.fixtures.Forgetful
import com.hafnium.it.fixtures.Inventory
import com.hafnium.it.fixtures.Overlap
import com.hafnium.it.fixtures.Pallet
import com.hafnium.it.fixtures.Secrets
import com.hafnium.it.fixtures.Shipment
import com.hafnium.it.fixtures.Wide
import com.hafnium.it.outside.AllParamsOutside
import com.hafnium.it.report.FrameReport
import com.thirdparty.InventoryService
import com.thirdparty.UserService
import com.thirdparty.db.OrderRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * @StackTraceParams and wildcard [augment.params] entries; runs with the agent, see build.gradle.kts. [FrameReport]
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
    fun `an annotation outside the @ entries is ignored`() {
        assertEquals("run", report.thrown<IllegalStateException, _>("AllParamsOutside().run(1)", AllParamsOutside()) { it.run(1) }.method())
    }

    @Test
    fun `a parameter selected twice is shown once`() {
        assertEquals("op{x=1, y=2}", report.thrown<IllegalStateException, _>("Overlap().op(1, 2)", Overlap()) { it.op(1, 2) }.method())
    }

    @Test
    fun `all selected parameters are shown`() {
        val e = report.thrown<IllegalStateException, _>("Wide().ten(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)", Wide()) { it.ten(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) }
        assertEquals("ten{a=1, b=2, c=3, d=4, e=5, f=6, g=7, h=8, i=9, j=10}", e.method())
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
    fun `a hashed parameter that is null stays null`() {
        assertEquals("forget{email=null}", report.thrown<IllegalStateException, _>("Forgetful().forget(null)", Forgetful()) { it.forget(null) }.method())
    }

    @Test
    fun `constructors show their parameter ids, never a receiver id`() {
        val shipment = report.thrown<IllegalArgumentException>("Shipment(42, \"ann@example.com\", 0)") { Shipment(42, "ann@example.com", 0) }
        assertEquals("com.hafnium.it.fixtures.Shipment", shipment.stackTrace[0].className)
        assertEquals("<init>{orderId=42, email=#71d4f55f}", shipment.method())
    }

    @Test
    fun `an exception from the constructor called with this gets no ids in the delegating constructor`() {
        val pallet = report.thrown<IllegalStateException>("Pallet(\"big\", 200L)") { Pallet("big", 200L) }
        assertEquals("<init>{size=200}", pallet.method(0))
        assertEquals("<init>", pallet.method(1))
    }

    @Test
    fun `a delegating constructor shows its ids for exceptions from its own body`() {
        val pallet = report.thrown<IllegalArgumentException>("Pallet(\"\", 1L)") { Pallet("", 1L) }
        assertEquals("<init>{label=, size=1}", pallet.method(0))
    }

    @Test
    fun `an exception while computing the arguments of super gets the ids`() {
        val crate = report.thrown<IllegalArgumentException>("Crate(null, 3)") { Crate(null, 3) }
        assertEquals("com.hafnium.it.fixtures.Crate", crate.stackTrace[0].className)
        assertEquals("<init>{code=null, size=3}", crate.method())
    }

    @Test
    fun `an exception from the superclass constructor gets no ids in the subclass constructor`() {
        val parcel = report.thrown<IllegalArgumentException>("ExpressParcel(\"\", 1)") { ExpressParcel("", 1) }
        assertEquals("com.hafnium.it.fixtures.Parcel", parcel.stackTrace[0].className)
        assertEquals("<init>", parcel.method(0))
        assertEquals("com.hafnium.it.fixtures.ExpressParcel", parcel.stackTrace[1].className)
        assertEquals("<init>", parcel.method(1))
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
        val report = FrameReport("Parameter ids", basePackage = "com.hafnium.it.fixtures", ownPackage = "com.hafnium.it")
    }
}
