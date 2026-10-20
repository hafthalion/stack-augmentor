package com.hafnium.it

import com.hafnium.it.fixtures.Coroutines
import com.hafnium.it.fixtures.Reassigning
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What the frames of Kotlin functions show whose JVM form differs from their source; runs with the agent. */
class KotlinFunctionsTest {

    private val coroutines = Coroutines("c-1")

    /** Runs [block] as a coroutine, calls [resume] once it suspended, and returns what it threw. */
    private fun suspendedThrownBy(block: suspend () -> Unit, resume: () -> Unit = {}): Throwable {
        var failure: Throwable? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { failure = it.exceptionOrNull() })
        resume()
        return failure ?: throw AssertionError("the coroutine did not throw")
    }

    private fun thrownBy(block: () -> Unit): Throwable = runCatching(block).exceptionOrNull() ?: throw AssertionError("did not throw")

    private fun Throwable.frame(index: Int = 0): String = stackTrace[index].className.substringAfterLast('.') + "." + stackTrace[index].methodName

    @Test
    fun `a suspend function that throws before it suspends shows its parameter values`() {
        assertEquals("Coroutines{id=c-1}.failNow{key=k-1}", suspendedThrownBy({ coroutines.failNow("k-1") }).frame())
    }

    @Test
    fun `a suspend function that throws after it resumed shows the values it kept`() {
        val thrown = suspendedThrownBy({ coroutines.load("k-2") }) { coroutines.waiting!!.resumeWith(Result.success(Unit)) }
        // The resumed call gets placeholders for its arguments, and restores the values it kept before the suspension.
        assertEquals("Coroutines{id=c-1}.load{key=k-2}", thrown.frame())
    }

    @Test
    fun `a suspend function does not keep a value it no longer needs after a suspension`() {
        val thrown = suspendedThrownBy({ coroutines.forget("k-5") }) { coroutines.waiting!!.resumeWith(Result.success(Unit)) }
        assertEquals("Coroutines{id=c-1}.forget{key=null}", thrown.frame())
    }

    @Test
    fun `a reassigned parameter shows its new value`() {
        assertEquals("run{arg0=new-k-6}", thrownBy { Reassigning().run("k-6") }.stackTrace[0].methodName)
    }

    @Test
    fun `default arguments are shown, and the generated wrapper stays unchanged`() {
        val thrown = thrownBy { coroutines.withDefault("k-3") }
        assertEquals("Coroutines{id=c-1}.withDefault{key=k-3, count=3}", thrown.frame())
        assertEquals("Coroutines.withDefault\$default", thrown.frame(1))
    }

    @Test
    fun `an inline function has no frame, the function it is inlined into shows its own values`() {
        val thrown = thrownBy { coroutines.callsInlined("k-4") }
        assertEquals("Coroutines{id=c-1}.callsInlined{outer=k-4}", thrown.frame())
    }
}
