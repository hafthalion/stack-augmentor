package com.hafnium.stackaugmentor.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** The test JVM opens java.lang to the unnamed module (see build.gradle.kts), as the Java agent does. */
class StackTracesTest {

    companion object {
        @JvmStatic
        fun both(): List<StackTraces> = listOf(StackTraces.copying(), StackTraces.inPlace())
    }

    private val replacement = StackTraceElement("Replaced", "frame", "Replaced.java", 1)

    private fun thrown(): Throwable = try {
        throw IllegalStateException("fail")
    } catch (e: IllegalStateException) {
        e
    }

    @ParameterizedTest
    @MethodSource("both")
    fun `a written frame shows in the throwable's stack trace`(stackTraces: StackTraces) {
        val thrown = thrown()
        val original = thrown.stackTrace
        val trace = stackTraces.read(thrown)
        assertEquals(original.toList(), trace.toList())

        stackTraces.write(thrown, trace, 1, replacement)

        assertEquals(listOf(original[0], replacement) + original.drop(2), thrown.stackTrace.toList())
    }

    @ParameterizedTest
    @MethodSource("both")
    fun `a throwable without a writable stack trace has none to read`(stackTraces: StackTraces) {
        val thrown = object : RuntimeException("fail", null, true, false) {}

        assertEquals(0, stackTraces.read(thrown).size)
    }

    @ParameterizedTest
    @MethodSource("both")
    fun `the trace is read again after the application replaced it`(stackTraces: StackTraces) {
        val thrown = thrown()
        stackTraces.read(thrown)
        val replaced = arrayOf(replacement)
        thrown.stackTrace = replaced

        assertEquals(replaced.toList(), stackTraces.read(thrown).toList())
    }

    @Test
    fun `in place, reading does not copy the trace`() {
        val thrown = thrown()
        val stackTraces = StackTraces.inPlace()

        assertSame(stackTraces.read(thrown), stackTraces.read(thrown))
    }

    @Test
    fun `in place, a frame is not written into a trace the application replaced in the meantime`() {
        val thrown = thrown()
        val stackTraces = StackTraces.inPlace()
        val trace = stackTraces.read(thrown)
        val replaced = thrown.stackTrace
        thrown.stackTrace = replaced

        stackTraces.write(thrown, trace, 0, replacement)

        assertEquals(replaced.toList(), thrown.stackTrace.toList())
    }

    @Test
    fun `build-time handler writes in place when the application opens java lang`() {
        assertNotSame(StackTraces.copying(), ThrowHandler.runtimeStackTraces())
    }
}
