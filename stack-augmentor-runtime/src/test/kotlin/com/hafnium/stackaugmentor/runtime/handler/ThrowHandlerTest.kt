package com.hafnium.stackaugmentor.runtime.handler

import com.hafnium.stackaugmentor.AugmentedReceiver
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig
import com.hafnium.stackaugmentor.runtime.config.ConfigException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** This module has no stack-augmentor.toml on its test classpath, so ThrowHandler() uses the built-in configuration. */
class ThrowHandlerTest {

    class Annotated {
        @AugmentedReceiver
        val objectId = "a-1"

        fun fail(): Nothing = throw IllegalStateException("fail")
    }

    private fun thrownBy(target: Annotated): Throwable = try {
        target.fail()
    } catch (e: IllegalStateException) {
        e
    }

    @Test
    fun `build-time handler copies the trace when java lang is not open`() {
        assertSame(StackTraces.copying(), ThrowHandler.runtimeStackTraces())
    }

    @Test
    fun `build-time handler copies the trace and warns when in-place modification is asked for but java lang is not open`() {
        val property = StackTraces.IN_PLACE_MODIFICATION_PROPERTY
        System.setProperty(property, "true")
        val output = try {
            stderrOf { assertSame(StackTraces.copying(), ThrowHandler.runtimeStackTraces()) }
        } finally {
            System.clearProperty(property)
        }
        assertTrue(output.contains("WARN runtime: in-place modification needs java.lang open"), output)
        assertTrue(output.contains("--add-opens java.base/java.lang=ALL-UNNAMED"), output)
        assertEquals("", stderrOf { ThrowHandler.runtimeStackTraces() }, "no warning by default")
    }

    private fun stderrOf(action: () -> Unit): String {
        val originalErr = System.err
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            action()
        } finally {
            System.setErr(originalErr)
        }
        return err.toString(Charsets.UTF_8)
    }

    @Test
    fun `build-time handler uses the built-in configuration and warns without a runtime configuration`() {
        assertNull(AugmentorConfig.location(), "the test must run without -Dstackaugmentor.config")
        val target = Annotated()
        val built = thrownBy(target)
        val other = thrownBy(target)
        val originalErr = System.err
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            val handler = ThrowHandler()
            // Built with the built-in configuration too: it reads the annotations of all classes.
            handler.onThrow(target, built, AugmentorConfig.ANNOTATIONS, Annotated::class.java, Annotated::class.java.name, "fail", null, null)
            // Built with another key: the built-in configuration does not have it.
            handler.onThrow(target, other, Annotated::class.java.name + "::*", Annotated::class.java, Annotated::class.java.name, "fail", null, null)
        } finally {
            System.setErr(originalErr)
        }
        assertEquals(Annotated::class.java.name + "{objectId=a-1}", built.stackTrace[0].className)
        assertEquals(Annotated::class.java.name, other.stackTrace[0].className)
        assertTrue(err.toString(Charsets.UTF_8).contains("WARN runtime: " + ThrowHandler.NO_RUNTIME_CONFIG), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `an invalid runtime configuration is reported with its file, key and line`(@TempDir dir: Path) {
        val config = dir.resolve("stack-augmentor.toml")
        Files.writeString(config, "[augment]\nmaxValueLength = 1\n")
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
            output.contains("[stack-augmentor] ERROR runtime: stack-augmentor.toml, line 2: maxValueLength must be between 2 and 10000, was 1"),
            output,
        )
    }

    /** Loads [Annotated] itself, not through its parent, from the test classes and [dir], as an application server does. */
    private class Application(dir: Path) : URLClassLoader(
        arrayOf(Annotated::class.java.protectionDomain.codeSource.location, dir.toUri().toURL()),
        ThrowHandlerTest::class.java.classLoader,
    ) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            if (name != Annotated::class.java.name) {
                return super.loadClass(name, resolve)
            }
            findLoadedClass(name) ?: findClass(name)
        }
    }

    private fun thrownByOwn(target: Any): Throwable = try {
        target.javaClass.getMethod("fail").invoke(target)
        throw AssertionError("fail() returned")
    } catch (e: java.lang.reflect.InvocationTargetException) {
        e.cause!!
    }

    @Test
    fun `a class uses the context configuration of its own class loader`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("stack-augmentor-context.toml"), "[augment]\nreceiverFormat = \"<\$value>\"\n[augment.exceptions]\n\"java.lang.Exception+\" = true\n")
        val handler = ThrowHandler()
        Application(dir).use { application ->
            val type = application.loadClass(Annotated::class.java.name)
            assertSame(application, type.classLoader)
            val target = type.getConstructor().newInstance()
            val own = thrownByOwn(target)
            val output = stderrOf { handler.onThrow(target, own, AugmentorConfig.ANNOTATIONS, type, type.name, "fail", null, null) }
            assertEquals(Annotated::class.java.name + "<a-1>", own.stackTrace[0].className)
            assertTrue(!output.contains(ThrowHandler.NO_RUNTIME_CONFIG), output)
        }
        // The class of the test's own class loader keeps the base configuration, here the built-in one.
        val target = Annotated()
        val base = thrownBy(target)
        handler.onThrow(target, base, AugmentorConfig.ANNOTATIONS, Annotated::class.java, Annotated::class.java.name, "fail", null, null)
        assertEquals(Annotated::class.java.name + "{objectId=a-1}", base.stackTrace[0].className)
    }

    @Test
    fun `an invalid configuration of a class loader leaves its classes unchanged`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("stack-augmentor-context.toml"), "[augment]\nmaxValueLength = 1\n")
        val handler = ThrowHandler()
        Application(dir).use { application ->
            val type = application.loadClass(Annotated::class.java.name)
            val target = type.getConstructor().newInstance()
            val own = thrownByOwn(target)
            val output = stderrOf { handler.onThrow(target, own, AugmentorConfig.ANNOTATIONS, type, type.name, "fail", null, null) }
            assertEquals(Annotated::class.java.name, own.stackTrace[0].className)
            assertTrue(output.contains("ERROR runtime: "), output)
            assertTrue(output.contains("line 2: maxValueLength must be between 2 and 10000, was 1"), output)
        }
    }

    @Test
    fun `the annotations key uses the annotations, whatever the runtime annotations table says`() {
        val target = Annotated()
        val thrown = thrownBy(target)
        val config = AugmentorConfig.builder().annotations(mapOf(Annotated::class.java.name to true)).exceptions(mapOf("**" to true)).build()
        ThrowHandler(config).onThrow(target, thrown, AugmentorConfig.ANNOTATIONS, Annotated::class.java, Annotated::class.java.name, "fail", null, null)
        assertEquals("${Annotated::class.java.name}{objectId=a-1}", thrown.stackTrace[0].className)
        // A build-time class: its build decided to read the annotations, as for its parameters, so the runtime
        // configuration without [augment.annotations] augments it too.
        val unread = thrownBy(target)
        ThrowHandler(AugmentorConfig.builder().exceptions(mapOf("**" to true)).build())
            .onThrow(target, unread, AugmentorConfig.ANNOTATIONS, Annotated::class.java, Annotated::class.java.name, "fail", null, null)
        assertEquals("${Annotated::class.java.name}{objectId=a-1}", unread.stackTrace[0].className)
    }

    @Test
    fun `a label ending in # shows a hash of the value`() {
        val target = Annotated()
        val thrown = thrownBy(target)
        ThrowHandler(AugmentorConfig.builder().exceptions(mapOf("**" to true)).build()).onThrow(
            null, thrown, null, Annotated::class.java, Annotated::class.java.name, "fail", arrayOf<Any?>("ann@example.com", 42, null), arrayOf("email#", "id", "phone#"),
        )
        // SHA-256 of "ann@example.com" starts with 71d4f55f; null stays null.
        assertEquals("fail{email=#71d4f55f, id=42, phone=null}", thrown.stackTrace[0].methodName)
    }

    @Test
    fun `exceptions that no entry augments keep their frames`() {
        val annotations = mapOf(Annotated::class.java.name to true)
        val target = Annotated()
        val skipped = thrownBy(target)
        ThrowHandler(AugmentorConfig.builder().annotations(annotations).exceptions(mapOf("java.lang.*" to false)).build())
            .onThrow(target, skipped, AugmentorConfig.ANNOTATIONS, Annotated::class.java, Annotated::class.java.name, "fail", null, null)
        assertEquals(Annotated::class.java.name, skipped.stackTrace[0].className)

        val augmented = thrownBy(target)
        ThrowHandler(AugmentorConfig.builder().annotations(annotations).exceptions(mapOf("java.lang.IllegalStateException" to true)).build())
            .onThrow(target, augmented, AugmentorConfig.ANNOTATIONS, Annotated::class.java, Annotated::class.java.name, "fail", null, null)
        assertEquals("${Annotated::class.java.name}{objectId=a-1}", augmented.stackTrace[0].className)
    }
}
