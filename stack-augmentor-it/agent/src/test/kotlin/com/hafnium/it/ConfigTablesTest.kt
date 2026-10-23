package com.hafnium.it

import com.hafnium.it.fixtures.MultiKeyed
import com.hafnium.it.fixtures.Overridden
import com.hafnium.it.fixtures.ignored.Ignored
import com.hafnium.it.outside.ClassParamsViaMethods
import com.hafnium.it.outside.Dispatcher
import com.hafnium.it.outside.MethodAnnotationsOutside
import com.hafnium.it.outside.Selective
import com.hafnium.it.outside.WithoutAugmented
import com.hafnium.it.outside.PremiumAccount
import com.hafnium.it.outside.VipCustomer
import com.hafnium.it.report.FrameReport
import com.hafnium.it.report.ReceiverEntries
import com.thirdparty.InventoryAudit
import com.thirdparty.Ledger1
import com.thirdparty.Ledger12
import com.thirdparty.OrderLine
import com.thirdparty.Parcel
import com.thirdparty.SavingsAccount
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * [augment.receiver] and [augment.params] entries; runs with the agent, see stack-augmentor-global.toml.
 * [FrameReport] writes the frames each test caught to build/reports/frames/ConfigTablesTest.html.
 */
class ConfigTablesTest {

    /** The top frame of the exception that [call] throws on [receiver], recorded for the report. */
    private fun <R : Any> frame(call: String, receiver: R, block: (R) -> Unit): StackTraceElement =
        report.thrown<IllegalStateException, R>(call, receiver, block = block).stackTrace[0]

    @Test
    fun `wildcard class entry with a member`() {
        val frame = frame("SavingsAccount(\"S-1\").withdraw()", SavingsAccount("S-1")) { it.withdraw() }
        assertEquals("com.thirdparty.SavingsAccount{number=S-1}", frame.className)
        assertEquals("withdraw", frame.methodName)
    }

    @Test
    fun `a list of members shows each of them`() {
        val frame = frame("OrderLine(\"acme\", 3).cancel()", OrderLine("acme", 3)) { it.cancel() }
        assertEquals("com.thirdparty.OrderLine{tenant=acme, lineId=3}", frame.className)
    }

    @Test
    fun `several @AugmentedReceiver members are all shown`() {
        assertEquals("com.hafnium.it.fixtures.MultiKeyed{tenant=acme, orderId=42}", frame("MultiKeyed().fail()", MultiKeyed()) { it.fail() }.className)
    }

    @Test
    fun `a question mark in a pattern matches one character`() {
        assertEquals("com.thirdparty.Ledger1{code=L-1}", frame("Ledger1(\"L-1\").close()", Ledger1("L-1")) { it.close() }.className)
        assertEquals("com.thirdparty.Ledger12", frame("Ledger12(\"L-12\").close()", Ledger12("L-12")) { it.close() }.className)
    }

    @Test
    fun `methods after double colon in the key limit an entry to them, and one class may have several entries`() {
        assertEquals("com.thirdparty.Parcel{trackingCode=T-1}", frame("Parcel(\"T-1\").ship()", Parcel("T-1")) { it.ship() }.className)
        assertEquals("com.thirdparty.Parcel{trackingCode=T-1}", frame("Parcel(\"T-1\").cancelLater()", Parcel("T-1")) { it.cancelLater() }.className)
        assertEquals("com.thirdparty.Parcel{weight=2}", frame("Parcel(\"T-1\").weigh()", Parcel("T-1")) { it.weigh() }.className)
        assertEquals("com.thirdparty.Parcel", frame("Parcel(\"T-1\").inspect()", Parcel("T-1")) { it.inspect() }.className)
    }

    @Test
    fun `@Augmented on a method limits the annotations to the methods that have it`() {
        val send = frame("Dispatcher(\"H-1\").send(\"o-1\")", Dispatcher("H-1")) { it.send("o-1") }
        assertEquals("com.hafnium.it.outside.Dispatcher{hub=H-1}", send.className)
        assertEquals("send{order=o-1}", send.methodName)
        val track = frame("Dispatcher(\"H-1\").track(\"o-1\")", Dispatcher("H-1")) { it.track("o-1") }
        assertEquals("com.hafnium.it.outside.Dispatcher", track.className)
        assertEquals("track", track.methodName)
    }

    @Test
    fun `@Augmented on the type decides for the methods without their own`() {
        val run = frame("Selective(\"S-1\").run(3)", Selective("S-1")) { it.run(3) }
        assertEquals("com.hafnium.it.outside.Selective{code=S-1}", run.className)
        assertEquals("run{x=3}", run.methodName)
        val skip = frame("Selective(\"S-1\").skip(4)", Selective("S-1")) { it.skip(4) }
        assertEquals("com.hafnium.it.outside.Selective", skip.className)
        assertEquals("skip", skip.methodName)
    }

    @Test
    fun `a class without @Augmented is ignored`() {
        val frame = frame("WithoutAugmented(\"W-1\").run(6)", WithoutAugmented("W-1")) { it.run(6) }
        assertEquals("com.hafnium.it.outside.WithoutAugmented", frame.className)
        assertEquals("run", frame.methodName)
    }

    @Test
    fun `a class name without plus does not apply to subclasses, with plus it does`() {
        // "com.thirdparty.Customer::*" has no "+": the methods VipCustomer declares show no receiver member, those it
        // inherits from Customer do.
        assertEquals("com.hafnium.it.outside.VipCustomer", frame("VipCustomer(\"C-9\").upgrade()", VipCustomer("C-9")) { it.upgrade() }.className)
        assertEquals("com.thirdparty.Customer{customerId=C-9}", frame("VipCustomer(\"C-9\").rename()", VipCustomer("C-9")) { it.rename() }.className)
        // "com.thirdparty.SavingsAccount+::transfer" in [augment.params] applies to the subclass PremiumAccount.
        assertEquals("transfer{amount=5}", frame("PremiumAccount(\"P-1\").transfer(5)", PremiumAccount("P-1")) { it.transfer(5) }.methodName)
    }

    @Test
    fun `the exact explicit entry wins over the annotations`() {
        val frame = frame("Overridden().fail(1)", Overridden()) { it.fail(1) }
        assertEquals("com.hafnium.it.fixtures.Overridden{getId=o-1}", frame.className)
        assertEquals("fail", frame.methodName)
    }

    @Test
    fun `a '-' class entry drops the receiver members only`() {
        val frame = frame("Ignored().fail(3)", Ignored()) { it.fail(3) }
        assertEquals("com.hafnium.it.fixtures.ignored.Ignored", frame.className)
        // The parameter annotations are enabled by [augment.params], independently.
        assertEquals("fail{count=3}", frame.methodName)
    }

    @Test
    fun `a '-' method entry ignores the later entries`() {
        assertEquals("purge", frame("InventoryAudit().purge(\"x-1\")", InventoryAudit()) { it.purge("x-1") }.methodName)
        assertEquals("log{reason=disk full}", frame("InventoryAudit().log(\"disk full\", 2)", InventoryAudit()) { it.log("disk full", 2) }.methodName)
    }

    @Test
    fun `a method without a params entry uses its annotations where @Augmented selects it`() {
        val frame = frame("Overridden().failAnnotated(2)", Overridden()) { it.failAnnotated(2) }
        assertEquals("com.hafnium.it.fixtures.Overridden{getId=o-1}", frame.className)
        assertEquals("failAnnotated{y=2}", frame.methodName)

        val outside = frame("MethodAnnotationsOutside().run(7)", MethodAnnotationsOutside()) { it.run(7) }
        assertEquals("com.hafnium.it.outside.MethodAnnotationsOutside", outside.className)
        assertEquals("run{code=7}", outside.methodName)

        assertEquals("run{a=1, b=x}", frame("ClassParamsViaMethods().run(1, \"x\")", ClassParamsViaMethods()) { it.run(1, "x") }.methodName)
    }

    companion object {
        @JvmField
        @RegisterExtension
        val report = FrameReport(
            "Configuration tables",
            basePackage = "com.hafnium.it",
            entries = ReceiverEntries.load(Path.of("stack-augmentor-global.toml")),
        )
    }
}
