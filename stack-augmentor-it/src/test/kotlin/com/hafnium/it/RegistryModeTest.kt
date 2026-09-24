package com.hafnium.it

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.LoggingEvent
import com.hafnium.it.fixtures.Layers
import com.hafnium.it.fixtures.ManyParams
import com.hafnium.it.fixtures.ObjectClass
import com.hafnium.stackaugmentor.AugmentedStackTraces
import com.hafnium.stackaugmentor.logback.AugmentedThrowableConverter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.PrintWriter
import java.io.StringWriter
import java.util.function.Supplier

/**
 * Runs with `src/test/config/registry.properties`: the stack trace stays unchanged and the ids are
 * added when printing, with `frameFormat={simpleClass}{receiver}.{method}{params} @ {file}:{line}`
 * and `paramsFormat=({name}: {id}; ...)`.
 */
@Tag("registry")
class RegistryModeTest {

    @Test
    fun `stack trace is unchanged`() {
        val e = assertThrows<Exception> { ObjectClass().objectMethod(42) }
        assertEquals("com.hafnium.it.fixtures.ObjectClass", e.stackTrace[0].className)
        assertEquals("objectMethod", e.stackTrace[0].methodName)
        val printed = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        assertTrue(!printed.contains("objectId"), printed)
    }

    @Test
    fun `printer shows the ids in the configured format`() {
        val e = assertThrows<Exception> { ObjectClass().objectMethod(42) }
        val line = e.stackTrace[0].lineNumber
        val text = AugmentedStackTraces.format(e)
        assertTrue(text.startsWith("java.lang.Exception: An error has occured" + System.lineSeparator()), text)
        assertTrue(text.contains("\tat ObjectClass[objectId=object-1].objectMethod(orderId: 42) @ Fixtures.kt:$line"), text)
        // Frames without ids keep the JDK format.
        assertTrue(text.contains("\tat com.hafnium.it.RegistryModeTest."), text)
    }

    @Test
    fun `several parameters use the separator from paramsFormat`() {
        val e = assertThrows<IllegalStateException> { ManyParams().maybeFail(1, 2, 3, 4, 5, 6, true) }
        val text = AugmentedStackTraces.format(e)
        assertTrue(text.contains(".maybeFail(a: 1; b: 2; c: 3; d: 4; e: 5; f: 6) @ Fixtures.kt:"), text)
    }

    @Test
    fun `causes are printed with their ids`() {
        val e = assertThrows<RuntimeException> { Layers().wrap() }
        val text = AugmentedStackTraces.format(e)
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException: inner 2"), text)
        assertTrue(text.contains("\tat Layers[layer=layers].inner(step: 2) @ Fixtures.kt:"), text)
        assertTrue(text.contains("\tat Layers[layer=layers].wrap @ Fixtures.kt:"), text)
        assertTrue(text.contains(" more"), text)
    }

    @Test
    fun `logback converter`() {
        val context = LoggerContext()
        val layout = PatternLayout()
        layout.context = context
        layout.instanceConverterMap["aex"] = Supplier { AugmentedThrowableConverter() }
        layout.pattern = "%msg%n%aex"
        layout.start()

        val e = assertThrows<Exception> { ObjectClass().objectMethod(42) }
        val logger = context.getLogger("test")
        val event = LoggingEvent(javaClass.name, logger, Level.ERROR, "it failed", e, null)
        val text = layout.doLayout(event)

        assertTrue(text.startsWith("it failed" + System.lineSeparator() + "java.lang.Exception: An error has occured"), text)
        assertTrue(text.contains("\tat ObjectClass[objectId=object-1].objectMethod(orderId: 42) @ Fixtures.kt:"), text)
    }
}
