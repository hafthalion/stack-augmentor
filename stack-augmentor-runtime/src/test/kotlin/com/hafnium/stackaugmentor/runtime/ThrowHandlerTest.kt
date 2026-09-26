package com.hafnium.stackaugmentor.runtime

import com.hafnium.stackaugmentor.StackTraceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
    fun `build-time handler uses annotations of classes without an entry`() {
        assertNull(AugmentorConfig.location(null), "the test must run without -Dstackaugmentor.config")
        val target = Annotated()
        val thrown = thrownBy(target)
        ThrowHandler().onThrow(target, thrown, Annotated::class.java.name, "fail", null, null)
        assertEquals("${Annotated::class.java.name}{objectId=a-1}", thrown.stackTrace[0].className)
    }

    @Test
    fun `agent handler needs an entry`() {
        val target = Annotated()
        val thrown = thrownBy(target)
        ThrowHandler(AugmentorConfig()).onThrow(target, thrown, Annotated::class.java.name, "fail", null, null)
        assertEquals(Annotated::class.java.name, thrown.stackTrace[0].className)
    }
}
