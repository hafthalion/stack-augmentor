package com.hafnium.stackaugmentor.runtime

import com.hafnium.stackaugmentor.StackTraceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** This module has no stack-augmentor.toml on its test classpath, so ThrowHandler() uses the defaults. */
class ThrowHandlerTest {

    class Annotated {
        @StackTraceId
        val objectId = "a-1"

        fun fail(): Nothing = throw IllegalStateException("fail")
    }

    private fun thrownBy(target: Annotated): Throwable = try {
        target.fail()
    } catch (e: IllegalStateException) {
        e
    }

    @Test
    fun `build-time handler needs an entry and warns without a runtime configuration`() {
        assertNull(AugmentorConfig.location(null), "the test must run without -Dstackaugmentor.config")
        val target = Annotated()
        val thrown = thrownBy(target)
        val originalErr = System.err
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            ThrowHandler().onThrow(target, thrown, Annotated::class.java.name, "fail", null, null)
        } finally {
            System.setErr(originalErr)
        }
        assertEquals(Annotated::class.java.name, thrown.stackTrace[0].className)
        assertTrue(err.toString(Charsets.UTF_8).contains("WARN " + ThrowHandler.NO_RUNTIME_CONFIG), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `agent handler needs an entry`() {
        val target = Annotated()
        val thrown = thrownBy(target)
        ThrowHandler(AugmentorConfig()).onThrow(target, thrown, Annotated::class.java.name, "fail", null, null)
        assertEquals(Annotated::class.java.name, thrown.stackTrace[0].className)
    }

    @Test
    fun `an "@" entry uses the annotations`() {
        val target = Annotated()
        val thrown = thrownBy(target)
        val config = AugmentorConfig.builder().classes(mapOf(Annotated::class.java.name to IdSpec.Annotations())).build()
        ThrowHandler(config).onThrow(target, thrown, Annotated::class.java.name, "fail", null, null)
        assertEquals("${Annotated::class.java.name}{objectId=a-1}", thrown.stackTrace[0].className)
    }
}
