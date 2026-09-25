package com.hafnium.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs a Java-only application ([com.hafnium.it.fixtures.JavaMain]) in a JVM of its own, started with the agent
 * and a classpath without the Kotlin runtime; see build.gradle.kts.
 */
class WithoutKotlinTest {

    private class Result(val exitCode: Int, val output: String)

    private val agentJar = System.getProperty("stackaugmentor.it.agentJar")
    private val classpath = System.getProperty("stackaugmentor.it.javaClasspath")

    private fun run(config: Path): Result {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(java, "-javaagent:$agentJar=config=$config", "-cp", classpath, "com.hafnium.it.fixtures.JavaMain")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the application did not finish")
        return Result(process.exitValue(), output)
    }

    private fun config(dir: Path, text: String): Path =
        dir.resolve("stack-augmentor.toml").also { Files.writeString(it, text) }

    private fun assertNoKotlin(output: String) {
        assertFalse(output.contains("kotlin/"), output)
        assertFalse(output.contains("NoClassDefFoundError"), output)
        assertFalse(output.contains("ClassNotFoundException"), output)
    }

    @Test
    fun `the application's classpath has no Kotlin`() {
        val entries = classpath.split(File.pathSeparator)
        assertEquals(2, entries.size, classpath)
        assertTrue(entries.none { it.contains("kotlin", ignoreCase = true) }, classpath)
    }

    @Test
    fun `agent augments a Java application without the Kotlin runtime`(@TempDir dir: Path) {
        val result = run(config(dir, "debug = true\n[instrument]\nannotatedClasses = [\"com.hafnium.it.fixtures.**\"]\n"))
        assertEquals(0, result.exitCode, result.output)
        assertTrue(result.output.contains("at com.hafnium.it.fixtures.JavaFixture{key=java-1}.run{arg0=42}(JavaFixture.java:"), result.output)
        // Debug output exercises the logging, configuration and matching code paths as well.
        assertTrue(result.output.contains("[stack-augmentor] DEBUG instrumenting com.hafnium.it.fixtures.JavaFixture"), result.output)
        assertTrue(result.output.contains("[stack-augmentor] DEBUG id source of com.hafnium.it.fixtures.JavaFixture"), result.output)
        assertNoKotlin(result.output)
    }

    @Test
    fun `invalid configuration is reported without the Kotlin runtime`(@TempDir dir: Path) {
        val result = run(config(dir, "debug = false\n[augment]\nmaxIdLength = 1\n"))
        assertNotEquals(0, result.exitCode, result.output)
        assertTrue(
            result.output.contains("stack-augmentor.toml, line 3: maxIdLength must be between 2 and 10000, was 1"),
            result.output,
        )
        assertNoKotlin(result.output)
    }
}
