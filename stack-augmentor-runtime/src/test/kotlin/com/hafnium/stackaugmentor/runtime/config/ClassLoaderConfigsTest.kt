package com.hafnium.stackaugmentor.runtime.config

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.CodeSource
import java.security.ProtectionDomain
import java.security.cert.Certificate
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ClassLoaderConfigsTest {

    private fun configs(argument: AugmentorConfig? = null) =
        ClassLoaderConfigs.create(Startup.RUNTIME, argument, "agent.toml") { config: AugmentorConfig -> config }

    private fun directory(parent: Path, name: String, vararg files: Pair<String, String>): Path {
        val dir = Files.createDirectories(parent.resolve(name))
        for ((file, text) in files) {
            Files.createDirectories(dir.resolve(file).parent)
            Files.writeString(dir.resolve(file), text)
        }
        return dir
    }

    private fun jar(parent: Path, name: String, vararg files: Pair<String, String>): Path {
        val jar = parent.resolve(name)
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            for ((file, text) in files) {
                out.putNextEntry(JarEntry(file))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    private fun loader(vararg entries: Path, parent: ClassLoader = javaClass.classLoader) =
        URLClassLoader(entries.map { it.toUri().toURL() }.toTypedArray(), parent)

    /** The protection domain of a class loaded from this jar or directory. */
    private fun from(entry: Path) = ProtectionDomain(CodeSource(entry.toUri().toURL(), null as Array<Certificate>?), null)

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
    fun `layered configurations look for the first matching entry in their order and take each setting from the first that sets it`() {
        val global = AugmentorConfig.parse(
            """
            [augment]
            receiverFormat = "<${'$'}value>"
            [augment.exceptions]
            "com.acme.FlowException" = false
            [augment.receiver]
            "com.acme.Order::*" = "id"
            """.trimIndent(), "global.toml",
        )
        val local = AugmentorConfig.parse(
            """
            [augment]
            receiverFormat = "[${'$'}value]"
            paramsFormat = "<${'$'}value>"
            maxValueLength = 10
            [augment.exceptions]
            "com.acme.**" = true
            [augment.receiver]
            "com.acme.Order::*" = "number"
            "com.acme.Invoice::*" = "number"
            """.trimIndent(), "local.toml", false,
        )
        val config = AugmentorConfig.layered(listOf(global, local, AugmentorConfig.builtIn()))
        assertEquals("<\$value>", config.receiverFormat())
        assertEquals("<\$value>", config.paramsFormat())
        assertEquals(10, config.maxValueLength())
        assertEquals(AugmentorConfig.DEFAULT_FRAME_FORMAT, config.frameFormat())
        // The global entry decides, the local one with the same key is left out.
        assertEquals("id", AugmentorConfig.specText(config.classes()["com.acme.Order::*"]!!))
        assertEquals(listOf("com.acme.Order::*", "com.acme.Invoice::*"), config.classes().keys.toList())
        assertEquals(
            listOf("com.acme.FlowException", "com.acme.**", "java.lang.Exception+"),
            config.exceptions().keys.toList(),
        )
        // The built-in configuration comes last.
        assertEquals(mapOf("**" to true), config.annotations())
        assertTrue(config.augments(IllegalStateException::class.java))
        assertFalse(config.augments(Error::class.java))
    }

    @Test
    fun `a local configuration must not set the settings of the whole JVM`() {
        for (setting in listOf("debug = true", "inPlaceModification = false")) {
            val message = assertThrows<ConfigException> { AugmentorConfig.parse(setting, "local.toml", false) }.message!!
            assertTrue(message.startsWith("local.toml, line 1: '${setting.substringBefore(' ')}' applies to the whole JVM"), message)
        }
        assertTrue(AugmentorConfig.parse("debug = true", "global.toml", true).debug())
    }

    @Test
    fun `a local configuration applies to the classes of its own directory or jar only`(@TempDir dir: Path) {
        val orders = directory(dir, "orders", "stack-augmentor.toml" to "[augment]\nmaxValueLength = 10\n")
        val invoices = jar(dir, "invoices.jar", "stack-augmentor.toml" to "[augment]\nmaxValueLength = 20\n")
        val other = directory(dir, "other")
        val configs = configs()
        loader(orders, invoices, other).use { loader ->
            assertEquals(10, configs.forType(loader, "com.acme.Order", from(orders)).maxValueLength())
            assertEquals(20, configs.forType(loader, "com.acme.Invoice", from(invoices)).maxValueLength())
            // A class from elsewhere, also without a protection domain, uses the base configuration.
            assertSame(configs.base(), configs.forType(loader, "com.acme.Customer", from(other)))
            assertSame(configs.base(), configs.forType(loader, "com.acme.Customer", null))
        }
    }

    @Test
    fun `without a code source a class's source is where its class loader finds its class file`(@TempDir dir: Path) {
        val orders = directory(dir, "orders", "stack-augmentor.toml" to "[augment]\nmaxValueLength = 10\n", "com/acme/Order.class" to "")
        val invoices = jar(dir, "invoices.jar", "stack-augmentor.toml" to "[augment]\nmaxValueLength = 20\n", "com/acme/Invoice.class" to "")
        val configs = configs()
        loader(orders, invoices).use { loader ->
            assertEquals(10, configs.forType(loader, "com.acme.Order", null).maxValueLength())
            assertEquals(20, configs.forType(loader, "com.acme.Invoice", ProtectionDomain(null, null)).maxValueLength())
        }
    }

    @Test
    fun `the global configuration of a class loader applies to all its classes before their local ones`(@TempDir dir: Path) {
        val application = directory(
            dir, "application",
            "stack-augmentor-global.toml" to "debug = false\n[augment]\nreceiverFormat = \"<\$value>\"\n[augment.exceptions]\n\"**\" = false\n",
        )
        val library = jar(
            dir, "library.jar",
            "stack-augmentor.toml" to "[augment]\nreceiverFormat = \"[\$value]\"\nmaxValueLength = 10\n[augment.exceptions]\n\"**\" = true\n",
        )
        val configs = configs()
        loader(application, library).use { loader ->
            val config = configs.forType(loader, "com.acme.Library", from(library))
            assertEquals("<\$value>", config.receiverFormat())
            assertEquals(10, config.maxValueLength())
            assertFalse(config.augments(IllegalStateException::class.java))
            val other = configs.forType(loader, "com.acme.Application", from(application))
            assertEquals("<\$value>", other.receiverFormat())
            assertEquals(AugmentorConfig.DEFAULT_MAX_VALUE_LENGTH, other.maxValueLength())
            // A class loader below it without one of its own uses it too.
            loader(dir.resolve("none"), parent = loader).use { child ->
                assertEquals(other, configs.forType(child, "com.acme.Child", null))
            }
        }
    }

    @Test
    fun `the global configuration given to the JVM applies before those on the classpath`(@TempDir dir: Path) {
        val application = directory(dir, "application", "stack-augmentor-global.toml" to "[augment]\nreceiverFormat = \"<\$value>\"\nmaxValueLength = 10\n")
        val configs = configs(AugmentorConfig.parse("[augment]\nmaxValueLength = 30\n", "agent.toml"))
        assertEquals(30, configs.base().maxValueLength())
        loader(application).use { loader ->
            val config = configs.forType(loader, "com.acme.Order", from(application))
            assertEquals(30, config.maxValueLength())
            assertEquals("<\$value>", config.receiverFormat())
        }
    }

    @Test
    fun `a class loader may have one global configuration only`(@TempDir dir: Path) {
        val first = directory(dir, "first", "stack-augmentor-global.toml" to "")
        val second = directory(dir, "second", "stack-augmentor-global.toml" to "")
        val configs = configs()
        loader(first, second).use { loader ->
            val output = stderrOf { assertNull(configs.forType(loader, "com.acme.Order", from(first))) }
            assertTrue(output.contains("ERROR runtime: class loader "), output)
            assertTrue(output.contains("has several stack-augmentor-global.toml, but may have one only"), output)
            assertTrue(output.contains("are not augmented"), output)
        }
    }

    @Test
    fun `an invalid local configuration leaves the classes of its source unchanged only`(@TempDir dir: Path) {
        val invalid = directory(dir, "invalid", "stack-augmentor.toml" to "debug = true\n")
        val valid = directory(dir, "valid", "stack-augmentor.toml" to "[augment]\nmaxValueLength = 10\n")
        val configs = configs()
        loader(invalid, valid).use { loader ->
            val output = stderrOf { configs.register(loader) }
            assertTrue(output.contains("ERROR runtime: "), output)
            assertTrue(output.contains("line 1: 'debug' applies to the whole JVM"), output)
            assertTrue(output.contains("the classes of file:"), output)
            assertNull(configs.forType(loader, "com.acme.Order", from(invalid)))
            assertEquals(10, configs.forType(loader, "com.acme.Invoice", from(valid)).maxValueLength())
        }
    }

    @Test
    fun `inPlaceModification in the global configuration of an application class loader is ignored with a warning`(@TempDir dir: Path) {
        val application = directory(dir, "application", "stack-augmentor-global.toml" to "inPlaceModification = false\n")
        val configs = configs()
        loader(application).use { loader ->
            val output = stderrOf { configs.register(loader) }
            assertTrue(output.contains("WARN runtime: "), output)
            assertTrue(output.contains("inPlaceModification applies to the whole JVM"), output)
        }
    }

    @Test
    fun `sources compare jars and directories in one form`() {
        assertEquals("file:/app/a.jar", ClassLoaderConfigs.source("jar:file:/app/a.jar!/"))
        assertEquals("file:/app/a.jar", ClassLoaderConfigs.source("file:/app/a.jar"))
        assertEquals("file:/app/classes", ClassLoaderConfigs.source("file:///app/classes/"))
        assertEquals("nested:/app.jar/!BOOT-INF/classes", ClassLoaderConfigs.source("jar:nested:/app.jar/!BOOT-INF/classes/!/"))
        assertEquals("file:/app/a.jar", ClassLoaderConfigs.source(URL("jar:file:/app/a.jar!/stack-augmentor.toml")))
    }

    @Test
    fun `classes of the JVM and fixed configurations use the base configuration`() {
        val configs = configs()
        assertTrue(configs.builtInOnly())
        assertSame(configs.base(), configs.forType(null, "java.lang.String", null))
        assertSame(configs.base(), configs.forClass(String::class.java))
        assertEquals(AugmentorConfig.builtIn(), configs.base())
        val fixed = ClassLoaderConfigs.fixed("base")
        assertSame("base", fixed.forClass(ClassLoaderConfigsTest::class.java))
    }
}
