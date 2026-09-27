package com.hafnium.it

import com.hafnium.it.fixtures.Overridden
import com.hafnium.it.fixtures.ignored.Ignored
import com.hafnium.it.outside.ClassParamsViaMethods
import com.hafnium.it.outside.MethodAnnotationsOutside
import com.hafnium.it.outside.PremiumAccount
import com.thirdparty.InventoryAudit
import com.thirdparty.InventoryService
import com.thirdparty.SavingsAccount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** [instrument.classes] and [instrument.methods] entries; runs with the agent, see src/test/resources/stack-augmentor.toml. */
class ConfigTablesTest {

    private fun frame(block: () -> Unit): StackTraceElement = assertThrows<IllegalStateException> { block() }.stackTrace[0]

    @Test
    fun `wildcard class entry with a member`() {
        val frame = frame { SavingsAccount("S-1").withdraw() }
        assertEquals("com.thirdparty.SavingsAccount{number=S-1}", frame.className)
        assertEquals("withdraw", frame.methodName)
    }

    @Test
    fun `an entry of a superclass applies to subclasses`() {
        assertEquals("com.hafnium.it.outside.PremiumAccount{number=P-1}", frame { PremiumAccount("P-1").upgrade() }.className)
    }

    @Test
    fun `the exact explicit entry wins over the annotations`() {
        val frame = frame { Overridden().fail(1) }
        assertEquals("com.hafnium.it.fixtures.Overridden{getId=o-1}", frame.className)
        assertEquals("fail", frame.methodName)
    }

    @Test
    fun `a "-" class entry ignores the class`() {
        val frame = frame { Ignored().fail(3) }
        assertEquals("com.hafnium.it.fixtures.ignored.Ignored", frame.className)
        assertEquals("fail", frame.methodName)
    }

    @Test
    fun `a "-" method entry ignores the less specific entries`() {
        assertEquals("purge", frame { InventoryAudit().purge("x-1") }.methodName)
        assertEquals("log{reason=disk full}", frame { InventoryAudit().log("disk full", 2) }.methodName)
        assertEquals("reserve{sku=x-1, count=2}", frame { InventoryService().reserve("x-1", 2) }.methodName)
    }

    @Test
    fun `an @ method entry enables the parameter annotations`() {
        val frame = frame { Overridden().failAnnotated(2) }
        assertEquals("com.hafnium.it.fixtures.Overridden{getId=o-1}", frame.className)
        assertEquals("failAnnotated{why=2}", frame.methodName)

        val outside = frame { MethodAnnotationsOutside().run(7) }
        assertEquals("com.hafnium.it.outside.MethodAnnotationsOutside", outside.className)
        assertEquals("run{code=7}", outside.methodName)

        assertEquals("run{a=1, b=x}", frame { ClassParamsViaMethods().run(1, "x") }.methodName)
    }
}
