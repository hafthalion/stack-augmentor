package com.hafnium.it

import com.hafnium.it.fixtures.ArrayId
import com.hafnium.it.fixtures.BrokenId
import com.hafnium.it.fixtures.Canvas
import com.hafnium.it.fixtures.Chain
import com.hafnium.it.fixtures.Derived
import com.hafnium.it.fixtures.FailingInit
import com.hafnium.it.fixtures.GetterRegistry
import com.hafnium.it.fixtures.JavaFixture
import com.hafnium.it.fixtures.KeyedByMethod
import com.hafnium.it.fixtures.LambdaHolder
import com.hafnium.it.fixtures.Layers
import com.hafnium.it.fixtures.LongId
import com.hafnium.it.fixtures.ManyParams
import com.hafnium.it.fixtures.MultiLine
import com.hafnium.it.fixtures.NoTrace
import com.hafnium.it.fixtures.NoTraceException
import com.hafnium.it.fixtures.Node
import com.hafnium.it.fixtures.NullId
import com.hafnium.it.fixtures.ObjectClass
import com.hafnium.it.fixtures.ParenLabels
import com.hafnium.it.fixtures.Plain
import com.hafnium.it.fixtures.Point
import com.hafnium.it.fixtures.Prepared
import com.hafnium.it.fixtures.Registry
import com.hafnium.it.fixtures.Relay
import com.hafnium.it.fixtures.Shipping
import com.hafnium.it.fixtures.Unprintable
import com.hafnium.it.fixtures.WithToString
import com.hafnium.it.fixtures.staticWithParam
import com.hafnium.it.fixtures.staticWithoutParam
import com.hafnium.it.outside.DerivedOutside
import com.hafnium.it.outside.ParamsOnly
import com.hafnium.it.report.FrameReport
import com.hafnium.it.report.ReceiverEntries
import com.hafnium.stackaugmentor.ExceptionFormat
import com.thirdparty.Customer
import com.thirdparty.Order
import com.thirdparty.OrderService
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Runs with the agent and `src/test/resources/stack-augmentor.toml` attached; see build.gradle.kts. [FrameReport] writes
 * the frames each test caught to build/reports/frames/StackTraceIdsTest.html.
 */
class StackTraceIdsTest {

    private fun Throwable.frame(index: Int = 0): String = stackTrace[index].toString()

    private fun Throwable.printed(): String = StringWriter().also { printStackTrace(PrintWriter(it)) }.toString()

    @Test
    fun `receiver field and parameter id`() {
        val e = report.thrown<Exception, _>("ObjectClass().objectMethod(42)", ObjectClass()) { it.objectMethod(42) }
        assertTrue(
            e.frame().startsWith("com.hafnium.it.fixtures.ObjectClass{objectId=object-1}.objectMethod{orderId=42}(Fixtures.kt:"),
            e.frame(),
        )
        assertTrue(e.printed().contains("\tat com.hafnium.it.fixtures.ObjectClass{objectId=object-1}.objectMethod{orderId=42}(Fixtures.kt:"))
    }

    @Test
    fun `receiver id from an annotated method uses the method name`() {
        val e = report.thrown<IllegalStateException, _>("KeyedByMethod().fail()", KeyedByMethod()) { it.fail() }
        assertEquals("com.hafnium.it.fixtures.KeyedByMethod{key=k-1}", e.stackTrace[0].className)
    }

    @Test
    fun `inherited id`() {
        val e = report.thrown<IllegalStateException, _>("Derived().fail()", Derived()) { it.fail() }
        assertEquals("com.hafnium.it.fixtures.Derived{baseId=b1}", e.stackTrace[0].className)
    }

    @Test
    fun `annotations outside the @ entries are ignored, also of subclasses of matched classes`() {
        // No entry matches DerivedOutside; the "@" entry of its superclass Base does not apply to it.
        val derived = report.thrown<IllegalStateException, _>("DerivedOutside().fail()", DerivedOutside()) { it.fail() }
        assertEquals("com.hafnium.it.outside.DerivedOutside", derived.stackTrace[0].className)

        val withParam = report.thrown<IllegalStateException, _>("ParamsOnly().withParam(7)", ParamsOnly()) { it.withParam(7) }
        assertEquals("com.hafnium.it.outside.ParamsOnly", withParam.stackTrace[0].className)
        assertEquals("withParam", withParam.stackTrace[0].methodName)
    }

    @Test
    fun `many parameters of all kinds`() {
        val e = report.thrown<IllegalStateException, _>("ManyParams().six(1, 2L, \"x\", 1.5, true, 'z')", ManyParams()) { it.six(1, 2L, "x", 1.5, true, 'z') }
        assertEquals("six{a=1, b=2, c=x, d=1.5, e=true, f=z}", e.stackTrace[0].methodName)
    }

    @Test
    fun `object parameter is shown with toString(), not with the id source of its class`() {
        val e = report.thrown<IllegalStateException, _>("Shipping().ship(Order(4711))", Shipping()) { it.ship(Order(4711)) }
        assertEquals("com.hafnium.it.fixtures.Shipping{id=ship}", e.stackTrace[0].className)
        // Order has the entry "getOrderNumber()", but no toString(): Object.toString(), cut at maxIdLength = 20.
        assertEquals("ship{order=com.thirdparty.Orde…}", e.stackTrace[0].methodName)
    }

    @Test
    fun `null parameter`() {
        val e = report.thrown<IllegalStateException, _>("Shipping().ship(null)", Shipping()) { it.ship(null) }
        assertEquals("ship{order=null}", e.stackTrace[0].methodName)
    }

    @Test
    fun `overloads get their own labels`() {
        val byInt = report.thrown<IllegalStateException, _>("Shipping().op(3)", Shipping()) { it.op(3) }
        assertEquals("op{x=3}", byInt.stackTrace[0].methodName)
        val byName = report.thrown<IllegalStateException, _>("Shipping().op(\"abc\", 9)", Shipping()) { it.op("abc", 9) }
        assertEquals("op{name=abc}", byName.stackTrace[0].methodName)
    }

    @Test
    fun `static method with an id parameter shows parameters only`() {
        val e = report.thrown<IllegalStateException>("staticWithParam(5)") { staticWithParam(5) }
        assertEquals("com.hafnium.it.fixtures.FixturesKt", e.stackTrace[0].className)
        assertEquals("staticWithParam{code=5}", e.stackTrace[0].methodName)
    }

    @Test
    fun `static method without id parameters is unchanged`() {
        val e = report.thrown<IllegalStateException>("staticWithoutParam()") { staticWithoutParam() }
        assertEquals("com.hafnium.it.fixtures.FixturesKt", e.stackTrace[0].className)
        assertEquals("staticWithoutParam", e.stackTrace[0].methodName)
    }

    @Test
    fun `external configuration for classes you cannot annotate`() {
        val service = report.thrown<IllegalStateException, _>("OrderService().process(Order(4711), 3, \"rush\")", OrderService()) { it.process(Order(4711), 3, "rush") }
        assertEquals("com.thirdparty.OrderService", service.stackTrace[0].className)
        // The exact entry "process" selects order and #1; the less specific "com.thirdparty.OrderService.*" adds nothing.
        assertEquals("process{order=com.thirdparty.Orde…, quantity=3}", service.stackTrace[0].methodName)

        val customer = report.thrown<IllegalStateException, _>("Customer(\"c-9\").rename()", Customer("c-9")) { it.rename() }
        assertEquals("com.thirdparty.Customer{customerId=c-9}", customer.stackTrace[0].className)
    }

    @Test
    fun `without parameter names the label is argN`() {
        val e = report.thrown<IllegalStateException, _>("JavaFixture().run(5)", JavaFixture()) { it.run(5) }
        assertEquals("com.hafnium.it.fixtures.JavaFixture{key=java-1}", e.stackTrace[0].className)
        assertEquals("run{arg0=5}", e.stackTrace[0].methodName)
    }

    @Test
    fun `classes without an id source are not augmented, even with a toString`() {
        val withToString = report.thrown<IllegalStateException, _>("WithToString().fail()", WithToString()) { it.fail() }
        assertEquals("com.hafnium.it.fixtures.WithToString", withToString.stackTrace[0].className)

        val plain = report.thrown<IllegalStateException, _>("Plain().fail()", Plain()) { it.fail() }
        assertEquals("com.hafnium.it.fixtures.Plain", plain.stackTrace[0].className)
    }

    @Test
    fun `an id source that throws is shown as a question mark`() {
        val e = report.thrown<IllegalStateException, _>("BrokenId().fail()", BrokenId()) { it.fail() }
        assertEquals("fail", e.message)
        assertEquals("com.hafnium.it.fixtures.BrokenId{id=?}", e.stackTrace[0].className)
    }

    @Test
    fun `ids are kept on one line and capped`() {
        val multiLine = report.thrown<IllegalStateException, _>("MultiLine().fail()", MultiLine()) { it.fail() }
        assertEquals("com.hafnium.it.fixtures.MultiLine{text=line1 line2}", multiLine.stackTrace[0].className)

        // maxIdLength = 20 in stack-augmentor.toml
        val long = report.thrown<IllegalStateException, _>("LongId().fail()", LongId()) { it.fail() }
        assertEquals("com.hafnium.it.fixtures.LongId{text=${"x".repeat(19)}…}", long.stackTrace[0].className)
    }

    @Test
    fun `recursion puts the right ids on each frame`() {
        val list = Node("a", Node("b", Node("c", null)))
        val e = report.thrown<IllegalStateException, _>("list.walk(0)", list) { it.walk(0) }
        assertEquals("com.hafnium.it.fixtures.Node{name=c}", e.stackTrace[0].className)
        assertEquals("walk{depth=2}", e.stackTrace[0].methodName)
        assertEquals("com.hafnium.it.fixtures.Node{name=b}", e.stackTrace[1].className)
        assertEquals("walk{depth=1}", e.stackTrace[1].methodName)
        assertEquals("com.hafnium.it.fixtures.Node{name=a}", e.stackTrace[2].className)
        assertEquals("walk{depth=0}", e.stackTrace[2].methodName)
    }

    @Test
    fun `an exception created by the caller does not give the callee's ids to the caller's frame`() {
        val e = report.thrown<IllegalStateException, _>("Relay(\"a\", Relay(\"b\", null)).pass(0, null)", Relay("a", Relay("b", null))) { it.pass(0, null) }
        assertEquals("created by a", e.message)
        // The only pass frame is a's, where the exception was created; b threw it, but its frame is not in the trace.
        assertEquals("com.hafnium.it.fixtures.Relay{name=a}", e.stackTrace[0].className)
        assertEquals("pass{depth=0}", e.stackTrace[0].methodName)
        assertEquals(1, e.stackTrace.count { it.methodName.startsWith("pass") })
    }

    @Test
    fun `ids are kept free of parentheses`() {
        val e = report.thrown<IllegalStateException, _>("ParenLabels().fail(5)", ParenLabels()) { it.fail(5) }
        assertEquals("com.hafnium.it.fixtures.ParenLabels{id=id{7}}", e.stackTrace[0].className)
        assertEquals("fail{value=5}", e.stackTrace[0].methodName)
    }

    @Test
    fun `lambda frames are unchanged`() {
        val holder = LambdaHolder()
        val e = report.thrown<IllegalStateException, _>("holder.viaLambda()", holder) { it.viaLambda() }
        val lambdaFrame = e.stackTrace.first { it.methodName.contains("lambda") }
        assertEquals("com.hafnium.it.fixtures.LambdaHolder", lambdaFrame.className)
        assertNotNull(e.stackTrace.firstOrNull { it.className == "com.hafnium.it.fixtures.LambdaHolder{id=lambda}" && it.methodName == "viaLambda" })
    }

    @Test
    fun `frames below a catch get ids only from the live stack`() {
        val layers = Layers()
        val e = layers.catchAndReturn()
        report.record("Layers().catchAndReturn()", e, layers)
        assertEquals("com.hafnium.it.fixtures.Layers{layer=layers}", e.stackTrace[0].className)
        assertEquals("inner{step=1}", e.stackTrace[0].methodName)
        if (AgentMode.liveStack) {
            // Every frame on the stack when the exception was created.
            assertEquals("com.hafnium.it.fixtures.Layers{layer=layers}", e.stackTrace[1].className)
        } else {
            // The exception never left catchAndReturn, so that frame and the ones below keep their plain form.
            assertEquals("com.hafnium.it.fixtures.Layers", e.stackTrace[1].className)
        }
        assertEquals("catchAndReturn", e.stackTrace[1].methodName)
    }

    @Test
    fun `wrapped exceptions are both annotated`() {
        val e = report.thrown<RuntimeException, _>("Layers().wrap()", Layers()) { it.wrap() }
        assertEquals("com.hafnium.it.fixtures.Layers{layer=layers}", e.stackTrace[0].className)
        assertEquals("wrap", e.stackTrace[0].methodName)
        val cause = e.cause!!
        assertEquals("inner{step=2}", cause.stackTrace[0].methodName)
        val printed = e.printed()
        assertTrue(printed.contains("Caused by: java.lang.IllegalStateException: inner 2"), printed)
        assertTrue(printed.contains("Layers{layer=layers}.inner{step=2}("), printed)
    }

    @Test
    fun `a cause shows the ids of the frames it shares with the wrapper`() {
        val e = report.thrown<RuntimeException, _>("Layers().outerWrap()", Layers()) { it.outerWrap() }
        val outer = e.stackTrace.indexOfFirst { it.methodName == "outerWrap" }
        assertEquals("com.hafnium.it.fixtures.Layers{layer=layers}", e.stackTrace[outer].className)
        val cause = e.cause!!.stackTrace
        val shared = cause.size - (e.stackTrace.size - outer)
        // The same element, so that printing collapses it and the frames below into "... N more".
        assertEquals(e.stackTrace[outer], cause[shared])
        // The frame where wrap caught the cause: the same call as the wrapper's, at another line.
        val wrap = cause.single { it.methodName == "wrap" }
        assertEquals("com.hafnium.it.fixtures.Layers{layer=layers}", wrap.className)
        assertTrue(wrap.lineNumber != e.stackTrace.single { it.methodName == "wrap" }.lineNumber)
        val printed = e.printed()
        assertTrue(printed.contains("\t... ${e.stackTrace.size - outer} more"), printed)
    }

    @Test
    fun `root cause first prints the shared frames once, with their ids`() {
        val e = report.thrown<RuntimeException, _>("Layers().outerWrap()", Layers()) { it.outerWrap() }
        val lines = ExceptionFormat.rootCauseFirst(e).lines()
        assertEquals("java.lang.IllegalStateException: inner 2", lines[0])
        assertTrue(lines[1].startsWith("\tat com.hafnium.it.fixtures.Layers{layer=layers}.inner"), lines[1])
        val wrapped = lines.indexOf("Wrapped by: java.lang.RuntimeException: wrapped")
        // The cause leaves out what it shares with the wrapper: all but its own frames, inner and wrap.
        val common = e.cause!!.stackTrace.size - (wrapped - 2)
        assertEquals("\t... $common common frames omitted", lines[wrapped - 1])
        val outer = lines.filter { it.contains(".outerWrap") }
        assertEquals(listOf("\tat ${e.stackTrace.first { it.methodName == "outerWrap" }}"), outer)
        assertTrue(outer.single().contains("Layers{layer=layers}"), outer.single())
    }

    @Test
    fun `a suppressed exception shows the ids of the frames it shares`() {
        val e = report.thrown<IllegalStateException, _>("Layers().outerClosing()", Layers()) { it.outerClosing() }
        val outer = e.stackTrace.indexOfFirst { it.methodName == "outerClosing" }
        assertEquals("com.hafnium.it.fixtures.Layers{layer=layers}", e.stackTrace[outer].className)
        val suppressed = e.suppressed.single().stackTrace
        assertEquals("com.hafnium.it.fixtures.Closer{name=closer}", suppressed[0].className)
        assertEquals(e.stackTrace[outer], suppressed[suppressed.size - (e.stackTrace.size - outer)])
    }

    @Test
    fun `exceptions without a writable stack trace are left alone`() {
        val e = report.thrown<NoTraceException, _>("NoTrace().fail()", NoTrace()) { it.fail() }
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

        val e = report.thrown<IllegalStateException, _>("fixture.maybeFail(1, 2, 3, 4, 5, 6, true)", fixture) { it.maybeFail(1, 2, 3, 4, 5, 6, true) }
        assertEquals("maybeFail{a=1, b=2, c=3, d=4, e=5, f=6}", e.stackTrace[0].methodName)
    }

    @Test
    fun `array ids are shown by their elements`() {
        val e = report.thrown<IllegalStateException, _>("ArrayId().fail(arrayOf(\"a\", \"b\"))", ArrayId()) { it.fail(arrayOf("a", "b")) }
        assertEquals("com.hafnium.it.fixtures.ArrayId{codes=[1, 2]}", e.stackTrace[0].className)
        assertEquals("fail{tags=[a, b]}", e.stackTrace[0].methodName)
    }

    @Test
    fun `null ids are shown as null`() {
        val e = report.thrown<IllegalStateException, _>("NullId().fail(null)", NullId()) { it.fail(null) }
        assertEquals("com.hafnium.it.fixtures.NullId{id=null}", e.stackTrace[0].className)
        assertEquals("fail{note=null}", e.stackTrace[0].methodName)
    }

    @Test
    fun `parentheses in a parameter's toString() are replaced`() {
        val e = report.thrown<IllegalStateException, _>("Canvas().draw(Point(1))", Canvas()) { it.draw(Point(1)) }
        assertEquals("com.hafnium.it.fixtures.Canvas{id=canvas}", e.stackTrace[0].className)
        assertEquals("draw{point=Point{x=1}}", e.stackTrace[0].methodName)
    }

    @Test
    fun `a parameter whose toString() throws is shown as a question mark`() {
        val e = report.thrown<IllegalStateException, _>("Canvas().print(Unprintable())", Canvas()) { it.print(Unprintable()) }
        assertEquals("print", e.message)
        assertEquals("print{item=?}", e.stackTrace[0].methodName)
    }

    @Test
    fun `constructors without selected parameters are unchanged`() {
        val e = report.thrown<IllegalStateException>("FailingInit(\"\")") { FailingInit("") }
        assertEquals("empty id", e.message)
        val init = e.stackTrace.first { it.methodName == "<init>" }
        assertEquals("com.hafnium.it.fixtures.FailingInit", init.className)
        assertFalse(e.stackTrace.any { it.className.contains('{') || it.methodName.contains('{') }, e.stackTrace.joinToString())
    }

    @Test
    fun `a Kotlin object takes its receiver id from an annotated getter, not from its static field`() {
        val field = report.thrown<IllegalStateException, _>("Registry.fail(\"k\")", Registry) { it.fail("k") }
        assertEquals("com.hafnium.it.fixtures.Registry", field.stackTrace[0].className)
        assertEquals("fail{key=k}", field.stackTrace[0].methodName)

        val getter = report.thrown<IllegalStateException, _>("GetterRegistry.fail(\"k\")", GetterRegistry) { it.fail("k") }
        assertEquals("com.hafnium.it.fixtures.GetterRegistry{getName=getter-registry}", getter.stackTrace[0].className)
        assertEquals("fail{key=k}", getter.stackTrace[0].methodName)
    }

    @Test
    fun `private methods get ids like public ones`() {
        val e = report.thrown<IllegalStateException, _>("Chain().start(1)", Chain()) { it.start(1) }
        assertEquals("com.hafnium.it.fixtures.Chain{id=chain}", e.stackTrace[0].className)
        assertEquals("step{n=2}", e.stackTrace[0].methodName)
        assertEquals("com.hafnium.it.fixtures.Chain{id=chain}", e.stackTrace[1].className)
        assertEquals("start{n=1}", e.stackTrace[1].methodName)
    }

    @Test
    fun `the synthetic method for default arguments is unchanged`() {
        val e = report.thrown<IllegalStateException, _>("Chain().withDefault(1)", Chain()) { it.withDefault(1) }
        assertEquals("com.hafnium.it.fixtures.Chain{id=chain}", e.stackTrace[0].className)
        assertEquals("withDefault{a=1, b=2}", e.stackTrace[0].methodName)
        assertEquals("com.hafnium.it.fixtures.Chain", e.stackTrace[1].className)
        assertEquals("withDefault\$default", e.stackTrace[1].methodName)
    }

    @Test
    fun `a rethrown exception gets the ids of the frames it leaves`() {
        val e = report.thrown<IllegalStateException, _>("Chain().rethrow(4)", Chain()) { it.rethrow(4) }
        assertEquals("step{n=4}", e.stackTrace[0].methodName)
        assertEquals("com.hafnium.it.fixtures.Chain{id=chain}", e.stackTrace[1].className)
        assertEquals("rethrow{n=4}", e.stackTrace[1].methodName)
    }

    @Test
    fun `an exception created outside the methods it leaves is unchanged`() {
        val e = report.thrown<IllegalStateException, _>("Prepared().throwCreated()", Prepared()) { it.throwCreated() }
        assertEquals("created in the constructor", e.message)
        assertFalse(e.stackTrace.any { it.className.contains('{') || it.methodName.contains('{') }, e.stackTrace.joinToString())
    }

    @Test
    fun `a reused exception instance keeps the ids of its first throw`() {
        val prepared = Prepared()
        val first = report.thrown<IllegalStateException, _>("Prepared().viaShared(1)", prepared) { it.viaShared(1) }
        assertEquals(listOf("throwShared{n=1}", "viaShared{n=1}"), first.stackTrace.take(2).map { it.methodName })
        val second = report.thrown<IllegalStateException, _>("prepared.viaShared(2)", prepared) { it.viaShared(2) }
        assertTrue(first === second)
        // A known limitation: the frames still show n=1.
        assertEquals(listOf("throwShared{n=1}", "viaShared{n=1}"), second.stackTrace.take(2).map { it.methodName })
    }

    @Test
    fun `ids are added on other threads too`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val e = report.thrown<ExecutionException>("executor.submit { Chain().start(7) }.get()") {
                executor.submit { Chain().start(7) }.get()
            }
            val cause = e.cause!!
            assertEquals("step{n=8}", cause.stackTrace[0].methodName)
            assertEquals("start{n=7}", cause.stackTrace[1].methodName)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `ids stay right once the JIT compiled the method`() {
        val target = ObjectClass()
        repeat(20_000) { n ->
            try {
                target.objectMethod(n)
            } catch (e: Exception) {
                // warming up
            }
        }
        val e = report.thrown<Exception, _>("ObjectClass().objectMethod(4242), after 20000 calls", target) { it.objectMethod(4242) }
        assertEquals("com.hafnium.it.fixtures.ObjectClass{objectId=object-1}", e.stackTrace[0].className)
        assertEquals("objectMethod{orderId=4242}", e.stackTrace[0].methodName)
    }

    @Test
    fun `a stack trace that the application replaced gets no ids from the live stack`() {
        assumeTrue(AgentMode.liveStack)
        val layers = Layers()
        val e = layers.catchAndReturn()
        val replaced = arrayOf(StackTraceElement("com.example.Elsewhere", "run", "Elsewhere.java", 3))
        e.stackTrace = replaced
        report.record("Layers().catchAndReturn(), then setStackTrace", e, layers)
        assertEquals(replaced.toList(), e.stackTrace.toList())
    }

    @Test
    fun `reading the stack trace again keeps the ids once`() {
        val e = report.thrown<IllegalStateException, _>("Layers().inner(3)", Layers()) { it.inner(3) }
        assertEquals("inner{step=3}", e.stackTrace[0].methodName)
        assertEquals("inner{step=3}", e.stackTrace[0].methodName)
        assertTrue(e.printed().contains("Layers{layer=layers}.inner{step=3}("), e.printed())
    }

    @Test
    fun `an exception that is never printed can be garbage-collected`() {
        // Thrown right by a configured method: while its constructor runs, the frame of that method references it.
        val unread = unreadException()
        repeat(50) {
            if (unread.get() == null) {
                return
            }
            System.gc()
            Thread.sleep(10)
        }
        assertNull(unread.get(), "the exception is still reachable")
    }

    private fun unreadException(): java.lang.ref.WeakReference<Throwable> = try {
        Layers().inner(3)
    } catch (e: IllegalStateException) {
        java.lang.ref.WeakReference(e)
    }

    @Test
    fun `untouched code keeps plain frames`() {
        val e = report.thrown<IllegalStateException>("throw IllegalStateException(\"here\")") { throw IllegalStateException("here") }
        assertFalse(e.stackTrace.any { it.className.contains('{') })
    }

    companion object {
        @JvmField
        @RegisterExtension
        val report = FrameReport(
            "Receiver and parameter ids",
            basePackage = "com.hafnium.it.fixtures",
            ownPackage = "com.hafnium.it",
            entries = ReceiverEntries.load(Path.of("src/test/resources/stack-augmentor.toml")),
        )
    }
}
