package com.hafnium.it

import com.hafnium.it.webapp.Webapp
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Applications in one server, each in a class loader of its own with its own stack-augmentor.toml: each class uses the
 * configuration of its class loader, the others the agent's.
 */
class ClassLoaderConfigTest {

    /** Loads the classes of [PACKAGE] itself, from the test classes, and its own stack-augmentor.toml from [config]. */
    private class Application(config: Path) : URLClassLoader(
        "webapp-" + config.fileName,
        arrayOf(Webapp::class.java.protectionDomain.codeSource.location, config.toUri().toURL()),
        ClassLoaderConfigTest::class.java.classLoader,
    ) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            if (!name.startsWith(PACKAGE)) {
                return super.loadClass(name, resolve)
            }
            findLoadedClass(name) ?: findClass(name)
        }
    }

    private fun application(dir: Path, name: String, toml: String): Application {
        val config = Files.createDirectories(dir.resolve(name))
        Files.writeString(config.resolve("stack-augmentor.toml"), toml)
        return Application(config)
    }

    /** The top frame of the exception that `Webapp(name).fail(code)` of [loader] throws. */
    private fun frame(loader: ClassLoader, name: String, code: Int): StackTraceElement {
        val type = loader.loadClass(Webapp::class.java.name)
        assertSame(loader, type.classLoader)
        val webapp = type.getConstructor(String::class.java).newInstance(name)
        try {
            type.getMethod("fail", Int::class.javaPrimitiveType).invoke(webapp, code)
        } catch (e: InvocationTargetException) {
            return e.cause!!.stackTrace[0]
        }
        throw AssertionError("fail() returned")
    }

    @Test
    fun `each application uses its own configuration`(@TempDir dir: Path) {
        val first = application(dir, "first", """
            [augment]
            receiverFormat = "<${'$'}value>"
            [augment.exceptions]
            "**" = true
            [augment.annotations]
            "com.hafnium.it.webapp.**" = true
            """.trimIndent())
        val second = application(dir, "second", """
            [augment]
            paramsFormat = "[${'$'}name:${'$'}value]"
            [augment.exceptions]
            "java.lang.RuntimeException+" = true
            [augment.annotations]
            "com.hafnium.it.webapp.Webapp" = true
            """.trimIndent())
        first.use { second.use {
            val firstFrame = frame(first, "a", 1)
            assertEquals("com.hafnium.it.webapp.Webapp<a>", firstFrame.className)
            assertEquals("fail{code=1}", firstFrame.methodName)
            val secondFrame = frame(second, "b", 2)
            assertEquals("com.hafnium.it.webapp.Webapp{name=b}", secondFrame.className)
            assertEquals("fail[code:2]", secondFrame.methodName)
        } }
        // The agent's configuration does not read the annotations of this package.
        val base = try {
            Webapp("c").fail(3)
        } catch (e: IllegalStateException) {
            e.stackTrace[0]
        }
        assertEquals("com.hafnium.it.webapp.Webapp", base.className)
        assertEquals("fail", base.methodName)
    }

    @Test
    fun `an application whose configuration does not augment the exception keeps its frames`(@TempDir dir: Path) {
        application(dir, "errors-only", """
            [augment.exceptions]
            "java.lang.Error+" = true
            [augment.annotations]
            "**" = true
            """.trimIndent()).use {
            assertEquals("com.hafnium.it.webapp.Webapp", frame(it, "d", 4).className)
        }
    }

    @Test
    fun `an invalid configuration of an application is reported and leaves its classes unchanged`(@TempDir dir: Path) {
        val originalErr = System.err
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val frame = try {
            application(dir, "invalid", "[augment]\nmaxValueLength = 1\n").use { frame(it, "e", 5) }
        } finally {
            System.setErr(originalErr)
        }
        assertEquals("com.hafnium.it.webapp.Webapp", frame.className)
        assertEquals("fail", frame.methodName)
        val output = err.toString(Charsets.UTF_8)
        assertTrue(output.contains("[stack-augmentor] ERROR agent: "), output)
        assertTrue(output.contains("line 2: maxValueLength must be between 2 and 10000, was 1"), output)
    }

    private companion object {
        val PACKAGE = Webapp::class.java.packageName + "."
    }
}
