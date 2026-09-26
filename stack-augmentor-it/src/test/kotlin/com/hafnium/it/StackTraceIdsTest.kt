package com.hafnium.it

import com.hafnium.it.fixtures.BrokenId
import com.hafnium.it.fixtures.Derived
import com.hafnium.it.fixtures.JavaFixture
import com.hafnium.it.fixtures.KeyedByMethod
import com.hafnium.it.fixtures.LambdaHolder
import com.hafnium.it.fixtures.Layers
import com.hafnium.it.fixtures.LongId
import com.hafnium.it.fixtures.ManyParams
import com.hafnium.it.fixtures.MultiLine
import com.hafnium.it.fixtures.Node
import com.hafnium.it.fixtures.NoTrace
import com.hafnium.it.fixtures.NoTraceException
import com.hafnium.it.fixtures.ObjectClass
import com.hafnium.it.fixtures.Plain
import com.hafnium.it.fixtures.Renamed
import com.hafnium.it.fixtures.Shipping
import com.hafnium.it.fixtures.WithToString
import com.hafnium.it.fixtures.staticWithParam
import com.hafnium.it.fixtures.staticWithoutParam
import com.hafnium.it.outside.DerivedOutside
import com.hafnium.it.outside.ParamsOnly
import com.thirdparty.Customer
import com.thirdparty.Order
import com.thirdparty.OrderService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.management.ManagementFactory

/** Runs with the agent and `src/test/resources/stack-augmentor.toml` attached; see build.gradle.kts. */
class StackTraceIdsTest {

    private fun Throwable.frame(index: Int = 0): String = stackTrace[index].toString()

    private fun Throwable.printed(): String = StringWriter().also { printStackTrace(PrintWriter(it)) }.toString()

    @Test
    fun `receiver field and parameter id`() {
        val e = assertThrows<Exception> { ObjectClass().objectMethod(42) }
        assertTrue(
            e.frame().startsWith("com.hafnium.it.fixtures.ObjectClass{objectId=object-1}.objectMethod{orderId=42}(Fixtures.kt:"),
            e.frame(),
        )
        assertTrue(e.printed().contains("\tat com.hafnium.it.fixtures.ObjectClass{objectId=object-1}.objectMethod{orderId=42}(Fixtures.kt:"))
    }

    @Test
    fun `receiver id from an annotated method uses the method name`() {
        val e = assertThrows<IllegalStateException> { KeyedByMethod().fail() }
        assertEquals("com.hafnium.it.fixtures.KeyedByMethod{key=k-1}", e.stackTrace[0].className)
    }

    @Test
    fun `annotation name overrides the label`() {
        val e = assertThrows<IllegalStateException> { Renamed().fail() }
        assertEquals("com.hafnium.it.fixtures.Renamed{user=bob}", e.stackTrace[0].className)
    }

    @Test
    fun `inherited id`() {
        val e = assertThrows<IllegalStateException> { Derived().fail() }
        assertEquals("com.hafnium.it.fixtures.Derived{baseId=b1}", e.stackTrace[0].className)
    }

    @Test
    fun `annotations outside the @ entries are ignored, but superclass entries apply`() {
        // No entry matches DerivedOutside, so the "@" entry of its superclass Base decides.
        val inherited = assertThrows<IllegalStateException> { DerivedOutside().fail() }
        assertEquals("com.hafnium.it.outside.DerivedOutside{baseId=b1}", inherited.stackTrace[0].className)

        val withParam = assertThrows<IllegalStateException> { ParamsOnly().withParam(7) }
        assertEquals("com.hafnium.it.outside.ParamsOnly", withParam.stackTrace[0].className)
        assertEquals("withParam", withParam.stackTrace[0].methodName)
    }

    @Test
    fun `many parameters of all kinds`() {
        val e = assertThrows<IllegalStateException> { ManyParams().six(1, 2L, "x", 1.5, true, 'z') }
        assertEquals("six{a=1, b=2, c=x, d=1.5, e=true, f=z}", e.stackTrace[0].methodName)
    }

    @Test
    fun `object parameter uses the id source of its class`() {
        val e = assertThrows<IllegalStateException> { Shipping().ship(Order(4711)) }
        assertEquals("com.hafnium.it.fixtures.Shipping{id=ship}", e.stackTrace[0].className)
        assertEquals("ship{order=4711}", e.stackTrace[0].methodName)
    }

    @Test
    fun `null parameter`() {
        val e = assertThrows<IllegalStateException> { Shipping().ship(null) }
        assertEquals("ship{order=null}", e.stackTrace[0].methodName)
    }

    @Test
    fun `overloads get their own labels`() {
        val byInt = assertThrows<IllegalStateException> { Shipping().op(3) }
        assertEquals("op{x=3}", byInt.stackTrace[0].methodName)
        val byName = assertThrows<IllegalStateException> { Shipping().op("abc", 9) }
        assertEquals("op{name=abc}", byName.stackTrace[0].methodName)
    }

    @Test
    fun `static method with an id parameter shows parameters only`() {
        val e = assertThrows<IllegalStateException> { staticWithParam(5) }
        assertEquals("com.hafnium.it.fixtures.FixturesKt", e.stackTrace[0].className)
        assertEquals("staticWithParam{code=5}", e.stackTrace[0].methodName)
    }

    @Test
    fun `static method without id parameters is unchanged`() {
        val e = assertThrows<IllegalStateException> { staticWithoutParam() }
        assertEquals("com.hafnium.it.fixtures.FixturesKt", e.stackTrace[0].className)
        assertEquals("staticWithoutParam", e.stackTrace[0].methodName)
    }

    @Test
    fun `external configuration for classes you cannot annotate`() {
        val service = assertThrows<IllegalStateException> { OrderService().process(Order(4711), 3, "rush") }
        assertEquals("com.thirdparty.OrderService", service.stackTrace[0].className)
        // "process" selects order and #1; the wildcard entry "com.thirdparty.OrderService.*" adds #2.
        assertEquals("process{order=4711, quantity=3, note=rush}", service.stackTrace[0].methodName)

        val customer = assertThrows<IllegalStateException> { Customer("c-9").rename() }
        assertEquals("com.thirdparty.Customer{customerId=c-9}", customer.stackTrace[0].className)
    }

    @Test
    fun `without parameter names the label is argN`() {
        val e = assertThrows<IllegalStateException> { JavaFixture().run(5) }
        assertEquals("com.hafnium.it.fixtures.JavaFixture{key=java-1}", e.stackTrace[0].className)
        assertEquals("run{arg0=5}", e.stackTrace[0].methodName)

        val named = assertThrows<IllegalStateException> { JavaFixture().named(6) }
        assertEquals("named{count=6}", named.stackTrace[0].methodName)
    }

    @Test
    fun `classes without an id source are not augmented, even with a toString`() {
        val withToString = assertThrows<IllegalStateException> { WithToString().fail() }
        assertEquals("com.hafnium.it.fixtures.WithToString", withToString.stackTrace[0].className)

        val plain = assertThrows<IllegalStateException> { Plain().fail() }
        assertEquals("com.hafnium.it.fixtures.Plain", plain.stackTrace[0].className)
    }

    @Test
    fun `an id source that throws is shown as a question mark`() {
        val e = assertThrows<IllegalStateException> { BrokenId().fail() }
        assertEquals("fail", e.message)
        assertEquals("com.hafnium.it.fixtures.BrokenId{id=?}", e.stackTrace[0].className)
    }

    @Test
    fun `ids are kept on one line and capped`() {
        val multiLine = assertThrows<IllegalStateException> { MultiLine().fail() }
        assertEquals("com.hafnium.it.fixtures.MultiLine{text=line1 line2}", multiLine.stackTrace[0].className)

        // maxIdLength = 20 in stack-augmentor.toml
        val long = assertThrows<IllegalStateException> { LongId().fail() }
        assertEquals("com.hafnium.it.fixtures.LongId{text=${"x".repeat(19)}…}", long.stackTrace[0].className)
    }

    @Test
    fun `recursion puts the right ids on each frame`() {
        val list = Node("a", Node("b", Node("c", null)))
        val e = assertThrows<IllegalStateException> { list.walk(0) }
        assertEquals("com.hafnium.it.fixtures.Node{name=c}", e.stackTrace[0].className)
        assertEquals("walk{depth=2}", e.stackTrace[0].methodName)
        assertEquals("com.hafnium.it.fixtures.Node{name=b}", e.stackTrace[1].className)
        assertEquals("walk{depth=1}", e.stackTrace[1].methodName)
        assertEquals("com.hafnium.it.fixtures.Node{name=a}", e.stackTrace[2].className)
        assertEquals("walk{depth=0}", e.stackTrace[2].methodName)
    }

    @Test
    fun `lambda frames are unchanged`() {
        val holder = LambdaHolder()
        val e = assertThrows<IllegalStateException> { holder.viaLambda() }
        val lambdaFrame = e.stackTrace.first { it.methodName.contains("lambda") }
        assertEquals("com.hafnium.it.fixtures.LambdaHolder", lambdaFrame.className)
        assertNotNull(e.stackTrace.firstOrNull { it.className == "com.hafnium.it.fixtures.LambdaHolder{id=lambda}" && it.methodName == "viaLambda" })
    }

    @Test
    fun `frames below a catch get no ids`() {
        val e = Layers().catchAndReturn()
        assertEquals("com.hafnium.it.fixtures.Layers{layer=layers}", e.stackTrace[0].className)
        assertEquals("inner{step=1}", e.stackTrace[0].methodName)
        // The exception never left catchAndReturn, so that frame and the ones below keep their plain form.
        assertEquals("com.hafnium.it.fixtures.Layers", e.stackTrace[1].className)
        assertEquals("catchAndReturn", e.stackTrace[1].methodName)
    }

    @Test
    fun `wrapped exceptions are both annotated`() {
        val e = assertThrows<RuntimeException> { Layers().wrap() }
        assertEquals("com.hafnium.it.fixtures.Layers{layer=layers}", e.stackTrace[0].className)
        assertEquals("wrap", e.stackTrace[0].methodName)
        val cause = e.cause!!
        assertEquals("inner{step=2}", cause.stackTrace[0].methodName)
        val printed = e.printed()
        assertTrue(printed.contains("Caused by: java.lang.IllegalStateException: inner 2"), printed)
        assertTrue(printed.contains("Layers{layer=layers}.inner{step=2}("), printed)
    }

    @Test
    fun `exceptions without a writable stack trace are left alone`() {
        val e = assertThrows<NoTraceException> { NoTrace().fail() }
        assertEquals(0, e.stackTrace.size)
    }

    @Test
    fun `normal returns allocate nothing`() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val fixture = ManyParams()
        // Values outside the Integer cache: boxing them on every call would allocate.
        fun run(times: Int): Long {
            var sum = 0L
            for (i in 0 until times) sum += fixture.maybeFail(1000 + i, 2000, 3000, 4000, 5000, 6000, false)
            return sum
        }
        run(10) // load classes, resolve call sites
        val before = threads.currentThreadAllocatedBytes
        val sum = run(2_000)
        val allocated = threads.currentThreadAllocatedBytes - before
        assertTrue(sum > 0)
        // Boxing six values plus an Object[6] would be over 100 bytes per call, ~200 KB in total.
        assertTrue(allocated < 32 * 1024, "allocated $allocated bytes for 2000 calls")

        val e = assertThrows<IllegalStateException> { fixture.maybeFail(1, 2, 3, 4, 5, 6, true) }
        assertEquals("maybeFail{a=1, b=2, c=3, d=4, e=5, f=6}", e.stackTrace[0].methodName)
    }

    @Test
    fun `untouched code keeps plain frames`() {
        val e = assertThrows<IllegalStateException> { throw IllegalStateException("here") }
        assertFalse(e.stackTrace.any { it.className.contains('{') })
    }
}
