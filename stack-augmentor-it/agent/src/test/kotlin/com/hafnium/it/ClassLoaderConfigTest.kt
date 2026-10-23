package com.hafnium.it

import com.hafnium.it.webapp.Webapp
import com.hafnium.it.webapp.WebappLibrary
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Applications in one server, each in a class loader of its own with its own stack-augmentor-context.toml, and jars with
 * their own stack-augmentor.toml: each class uses the agent's configuration, then the context one of its class loader,
 * then the local one of its jar or directory, then the built-in one.
 */
class ClassLoaderConfigTest {

    /** Loads the classes of [PACKAGE] itself, from [classes], and its configurations from [config]. */
    private class Application(config: Path, classes: List<URL> = listOf(FIXTURES.toUri().toURL())) : URLClassLoader(
        "webapp-" + config.fileName,
        (classes + config.toUri().toURL()).toTypedArray(),
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
        Files.writeString(config.resolve("stack-augmentor-context.toml"), toml)
        return Application(config)
    }

    /** The top frame of the exception that `Webapp(name).fail(code)`, or that of [fixture], of [loader] throws. */
    private fun frame(loader: ClassLoader, name: String, code: Int, fixture: Class<*> = Webapp::class.java): StackTraceElement {
        val type = loader.loadClass(fixture.name)
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
    fun `each application uses its own context configuration`(@TempDir dir: Path) {
        val first = application(dir, "first", """
            [augment]
            receiverFormat = "<${'$'}value>"
            """.trimIndent())
        val second = application(dir, "second", """
            [augment]
            paramsFormat = "[${'$'}name:${'$'}value]"
            """.trimIndent())
        first.use { second.use {
            val firstFrame = frame(first, "a", 1)
            assertEquals("com.hafnium.it.webapp.Webapp<a>", firstFrame.className)
            assertEquals("fail{code=1}", firstFrame.methodName)
            val secondFrame = frame(second, "b", 2)
            assertEquals("com.hafnium.it.webapp.Webapp{name=b}", secondFrame.className)
            assertEquals("fail[code:2]", secondFrame.methodName)
        } }
        // The class of the system class loader: the agent's configuration, with the default formats.
        val base = try {
            Webapp("c").fail(3)
        } catch (e: IllegalStateException) {
            e.stackTrace[0]
        }
        assertEquals("com.hafnium.it.webapp.Webapp{name=c}", base.className)
        assertEquals("fail{code=3}", base.methodName)
    }

    @Test
    fun `the agent's configuration applies before an application's`(@TempDir dir: Path) {
        // The agent's configuration augments all throwables, and cuts values off at 20 characters.
        application(dir, "errors-only", """
            [augment]
            maxValueLength = 64
            [augment.exceptions]
            "java.lang.Error+" = true
            "**" = false
            """.trimIndent()).use {
            assertEquals("com.hafnium.it.webapp.Webapp{name=0123456789012345678…}", frame(it, "0123456789012345678901234", 4).className)
        }
    }

    @Test
    fun `a local configuration applies to the classes of its own jar or directory`(@TempDir dir: Path) {
        // The class files of the package, with a local configuration next to them.
        val classes = Files.createDirectories(dir.resolve("classes"))
        val source = FIXTURES
        val packageDir = PACKAGE.removeSuffix(".").replace('.', '/')
        Files.list(source.resolve(packageDir)).use { files ->
            for (file in files) {
                Files.createDirectories(classes.resolve(packageDir))
                Files.copy(file, classes.resolve(packageDir).resolve(file.fileName))
            }
        }
        Files.writeString(classes.resolve("stack-augmentor.toml"), "[augment]\nreceiverFormat = \"<local ${'$'}value>\"\n")
        // The context configuration of the class loader applies first, here to the other directory only.
        val config = Files.createDirectories(dir.resolve("config"))
        Files.writeString(config.resolve("stack-augmentor.toml"), "[augment]\nreceiverFormat = \"[other ${'$'}value]\"\n")
        Application(config, listOf(classes.toUri().toURL())).use {
            assertEquals("com.hafnium.it.webapp.Webapp<local f>", frame(it, "f", 6).className)
        }
    }

    /** A jar with the class file of [fixture] and a stack-augmentor.toml of [toml]. */
    private fun jar(dir: Path, name: String, fixture: Class<*>, toml: String): Path {
        val jar = dir.resolve(name)
        val classFile = fixture.name.replace('.', '/') + ".class"
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry(classFile))
            Files.copy(FIXTURES.resolve(classFile), out)
            out.closeEntry()
            out.putNextEntry(JarEntry("stack-augmentor.toml"))
            out.write(toml.toByteArray())
            out.closeEntry()
        }
        return jar
    }

    @Test
    fun `each jar of an application uses its own local configuration`(@TempDir dir: Path) {
        val application = jar(dir, "application.jar", Webapp::class.java, "[augment]\nreceiverFormat = \"<application ${'$'}value>\"\n")
        val library = jar(dir, "library.jar", WebappLibrary::class.java, "[augment]\nreceiverFormat = \"<library ${'$'}value>\"\n" +
            "paramsFormat = \"[${'$'}name:${'$'}value]\"\n")
        val config = Files.createDirectories(dir.resolve("config"))
        Application(config, listOf(application, library).map { it.toUri().toURL() }).use {
            val applicationFrame = frame(it, "g", 7)
            assertEquals("com.hafnium.it.webapp.Webapp<application g>", applicationFrame.className)
            assertEquals("fail{code=7}", applicationFrame.methodName)
            val libraryFrame = frame(it, "h", 8, WebappLibrary::class.java)
            assertEquals("com.hafnium.it.webapp.WebappLibrary<library h>", libraryFrame.className)
            assertEquals("fail[code:8]", libraryFrame.methodName)
        }
    }

    @Test
    fun `an application's class loader can be collected after use`(@TempDir dir: Path) {
        // With a context and a local configuration, so that the agent keeps configurations for the class loader and its jar.
        val application = jar(dir, "application.jar", Webapp::class.java, "[augment]\nreceiverFormat = \"<application ${'$'}value>\"\n")
        val config = Files.createDirectories(dir.resolve("config"))
        Files.writeString(config.resolve("stack-augmentor-context.toml"), "[augment]\nparamsFormat = \"[${'$'}name:${'$'}value]\"\n")
        fun undeployed(): WeakReference<ClassLoader> {
            val loader = Application(config, listOf(application.toUri().toURL()))
            assertEquals("com.hafnium.it.webapp.Webapp<application g>", frame(loader, "g", 7).className)
            loader.close()
            return WeakReference(loader)
        }
        val loader = undeployed()
        for (attempt in 0 until 50) {
            if (loader.get() == null) {
                break
            }
            System.gc()
            Thread.sleep(20)
        }
        assertNull(loader.get(), "the class loader is still reachable")
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

        /** The directory with the class files of the fixtures. */
        val FIXTURES: Path = Path.of(Webapp::class.java.protectionDomain.codeSource.location.toURI())
    }
}
