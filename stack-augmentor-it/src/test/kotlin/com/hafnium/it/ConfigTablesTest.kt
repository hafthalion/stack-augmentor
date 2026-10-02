package com.hafnium.it

import com.hafnium.it.fixtures.MultiKeyed
import com.hafnium.it.fixtures.Overridden
import com.hafnium.it.fixtures.ignored.Ignored
import com.hafnium.it.outside.ClassParamsViaMethods
import com.hafnium.it.outside.MethodAnnotationsOutside
import com.hafnium.it.outside.PremiumAccount
import com.hafnium.it.report.FrameReport
import com.hafnium.it.report.ReceiverEntries
import com.thirdparty.InventoryAudit
import com.thirdparty.InventoryService
import com.thirdparty.Ledger1
import com.thirdparty.Ledger12
import com.thirdparty.OrderLine
import com.thirdparty.SavingsAccount
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * [augment.receiver] and [augment.params] entries; runs with the agent, see src/test/resources/stack-augmentor.toml.
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
    fun `a list of members gives several receiver ids`() {
        val frame = frame("OrderLine(\"acme\", 3).cancel()", OrderLine("acme", 3)) { it.cancel() }
        assertEquals("com.thirdparty.OrderLine{tenant=acme, lineId=3}", frame.className)
    }

    @Test
    fun `several @StackTraceId members give several receiver ids`() {
        assertEquals("com.hafnium.it.fixtures.MultiKeyed{tenant=acme, orderId=42}", frame("MultiKeyed().fail()", MultiKeyed()) { it.fail() }.className)
    }

    @Test
    fun `a question mark in a pattern matches one character`() {
        assertEquals("com.thirdparty.Ledger1{code=L-1}", frame("Ledger1(\"L-1\").close()", Ledger1("L-1")) { it.close() }.className)
        assertEquals("com.thirdparty.Ledger12", frame("Ledger12(\"L-12\").close()", Ledger12("L-12")) { it.close() }.className)
    }

    @Test
    fun `an entry of a superclass does not apply to subclasses`() {
        // "com.thirdparty.*Account" matches SavingsAccount, not com.hafnium.it.outside.PremiumAccount. See InheritanceTest.
        assertEquals("com.hafnium.it.outside.PremiumAccount", frame("PremiumAccount(\"P-1\").upgrade()", PremiumAccount("P-1")) { it.upgrade() }.className)
    }

    @Test
    fun `the exact explicit entry wins over the annotations`() {
        val frame = frame("Overridden().fail(1)", Overridden()) { it.fail(1) }
        assertEquals("com.hafnium.it.fixtures.Overridden{getId=o-1}", frame.className)
        assertEquals("fail", frame.methodName)
    }

    @Test
    fun `a '-' class entry drops the receiver id only`() {
        val frame = frame("Ignored().fail(3)", Ignored()) { it.fail(3) }
        assertEquals("com.hafnium.it.fixtures.ignored.Ignored", frame.className)
        // The parameter annotations are enabled by [augment.params], independently.
        assertEquals("fail{count=3}", frame.methodName)
    }

    @Test
    fun `a '-' method entry ignores the less specific entries`() {
        assertEquals("purge", frame("InventoryAudit().purge(\"x-1\")", InventoryAudit()) { it.purge("x-1") }.methodName)
        assertEquals("log{reason=disk full}", frame("InventoryAudit().log(\"disk full\", 2)", InventoryAudit()) { it.log("disk full", 2) }.methodName)
        assertEquals("reserve{sku=x-1, count=2}", frame("InventoryService().reserve(\"x-1\", 2)", InventoryService()) { it.reserve("x-1", 2) }.methodName)
    }

    @Test
    fun `an @ method entry enables the parameter annotations`() {
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
            entries = ReceiverEntries.load(Path.of("src/test/resources/stack-augmentor.toml")),
        )
    }
}
