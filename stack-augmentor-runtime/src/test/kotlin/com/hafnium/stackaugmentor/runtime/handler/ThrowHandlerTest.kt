package com.hafnium.stackaugmentor.runtime.handler

import com.hafnium.stackaugmentor.StackTraceId
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig
import com.hafnium.stackaugmentor.runtime.config.ConfigException
import com.hafnium.stackaugmentor.runtime.config.IdSpec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
    fun `build-time handler copies the trace by default`() {
        assertSame(StackTraces.copying(), ThrowHandler.runtimeStackTraces(AugmentorConfig()))
    }

    @Test
    fun `build-time handler refuses in-place modification when java lang is not open`() {
        val config = AugmentorConfig.builder().inPlaceModification(true).build()
        val e = assertThrows(ConfigException::class.java) { ThrowHandler.runtimeStackTraces(config) }
        assertTrue(e.message!!.contains("--add-opens java.base/java.lang=ALL-UNNAMED"), e.message)
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
        assertTrue(err.toString(Charsets.UTF_8).contains("WARN runtime: " + ThrowHandler.NO_RUNTIME_CONFIG), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `an invalid runtime configuration is reported with its file, key and line`(@TempDir dir: Path) {
        val config = dir.resolve("stack-augmentor.toml")
        Files.writeString(config, "[augment]\nmaxIdLength = 1\n")
        val originalErr = System.err
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        System.setProperty(AugmentorConfig.CONFIG_PROPERTY, config.toString())
        try {
            assertThrows(ConfigException::class.java) { ThrowHandler() }
        } finally {
            System.clearProperty(AugmentorConfig.CONFIG_PROPERTY)
            System.setErr(originalErr)
        }
        val output = err.toString(Charsets.UTF_8)
        assertTrue(
            output.contains("[stack-augmentor] ERROR runtime: stack-augmentor.toml, line 2: maxIdLength must be between 2 and 10000, was 1"),
            output,
        )
    }

    @Test
    fun `the context class loader provides the configuration that the runtime jar's loader does not see`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("stack-augmentor.toml"), "[augment.receiver]\n\"${Annotated::class.java.name}\" = \"@\"\n")
        val thread = Thread.currentThread()
        val originalLoader = thread.contextClassLoader
        val originalErr = System.err
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val target = Annotated()
        val thrown = thrownBy(target)
        try {
            java.net.URLClassLoader(arrayOf(dir.toUri().toURL()), null).use { application ->
                thread.contextClassLoader = application
                ThrowHandler().onThrow(target, thrown, Annotated::class.java.name, "fail", null, null)
            }
        } finally {
            thread.contextClassLoader = originalLoader
            System.setErr(originalErr)
        }
        assertEquals(Annotated::class.java.name + "{objectId=a-1}", thrown.stackTrace[0].className)
        assertTrue(!err.toString(Charsets.UTF_8).contains(ThrowHandler.NO_RUNTIME_CONFIG), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `agent handler needs an entry`() {
        val target = Annotated()
        val thrown = thrownBy(target)
        ThrowHandler(AugmentorConfig()).onThrow(target, thrown, Annotated::class.java.name, "fail", null, null)
        assertEquals(Annotated::class.java.name, thrown.stackTrace[0].className)
    }

    @Test
    fun `an '@' entry uses the annotations`() {
        val target = Annotated()
        val thrown = thrownBy(target)
        val config = AugmentorConfig.builder().classes(mapOf(Annotated::class.java.name to IdSpec.Annotations())).build()
        ThrowHandler(config).onThrow(target, thrown, Annotated::class.java.name, "fail", null, null)
        assertEquals("${Annotated::class.java.name}{objectId=a-1}", thrown.stackTrace[0].className)
    }

    @Test
    fun `a label ending in # shows a hash of the value`() {
        val target = Annotated()
        val thrown = thrownBy(target)
        ThrowHandler(AugmentorConfig()).onThrow(
            null, thrown, Annotated::class.java.name, "fail", arrayOf<Any?>("ann@example.com", 42, null), arrayOf("email#", "id", "phone#"),
        )
        // SHA-256 of "ann@example.com" starts with 71d4f55f; null stays null.
        assertEquals("fail{email=#71d4f55f, id=42, phone=null}", thrown.stackTrace[0].methodName)
    }
}
