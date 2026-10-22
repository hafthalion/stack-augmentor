package com.hafnium.it

import com.hafnium.it.report.FrameReport
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir

/**
 * Runs a Java-only application ([com.hafnium.it.fixtures.JavaMain]) in a JVM of its own, started with the agent
 * and a classpath without the Kotlin runtime; see build.gradle.kts. [FrameReport] writes the frames the application
 * printed to build/reports/frames/WithoutKotlinTest.html.
 */
class WithoutKotlinTest {

    private class Result(val exitCode: Int, val output: String)

    private val agentJar = System.getProperty("stackaugmentor.it.agentJar")
    private val classpath = System.getProperty("stackaugmentor.it.javaClasspath")
    private val nativeLibrary: String? = System.getProperty("stackaugmentor.it.nativeLibrary")

    /** Runs JavaMain with the agent and this configuration, or without one, and these JVM options. */
    private fun run(config: Path?, vararg options: String): Result {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val agent = if (config != null) "-javaagent:$agentJar=config=$config" else "-javaagent:$agentJar"
        val command = listOfNotNull(java, nativeLibrary?.let { "-agentpath:$it" }, agent, *options, "-cp", classpath, "com.hafnium.it.fixtures.JavaMain")
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the application did not finish")
        return Result(process.exitValue(), output)
    }

    /** Records the frames of the stack trace the application printed. */
    private fun record(call: String, output: String) {
        val frames = output.lines().filter { it.startsWith("\tat ") }.map { it.removePrefix("\tat ").substringBefore('(') }
        report.record(call, frames)
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
        val result = run(config(dir, "debug = true\n[augment.exceptions]\n\"**\" = true\n[augment.annotations]\n\"com.hafnium.it.fixtures.**\" = true\n"))
        assertEquals(0, result.exitCode, result.output)
        record("JavaMain, with the agent and a configuration", result.output)
        assertTrue(result.output.contains("at com.hafnium.it.fixtures.JavaFixture{key=java-1}.run{arg0=42}(JavaFixture.java:"), result.output)
        // Debug output exercises the logging, configuration and matching code paths as well.
        assertTrue(result.output.contains("[stack-augmentor] DEBUG agent: configuration "), result.output)
        assertTrue(result.output.contains("[stack-augmentor] DEBUG agent: [augment] frameFormat="), result.output)
        if (AgentMode.liveStack) {
            assertTrue(result.output.contains("[stack-augmentor] DEBUG agent: reading frames from the live stack"), result.output)
            assertTrue(result.output.contains("[stack-augmentor] DEBUG live stack: frames of com.hafnium.it.fixtures.JavaFixture.run show"), result.output)
        } else {
            assertTrue(result.output.contains("[stack-augmentor] DEBUG instrumenting com.hafnium.it.fixtures.JavaFixture"), result.output)
        }
        assertTrue(result.output.contains("[stack-augmentor] DEBUG receiver members of com.hafnium.it.fixtures.JavaFixture"), result.output)
        assertNoKotlin(result.output)
    }

    @Test
    fun `an agent given twice starts once`(@TempDir dir: Path) {
        val config = config(dir, "debug = true\n[augment.exceptions]\n\"**\" = true\n[augment.annotations]\n\"com.hafnium.it.fixtures.**\" = true\n")
        val result = run(config, "-javaagent:$agentJar=config=$config")
        assertEquals(0, result.exitCode, result.output)
        assertEquals(1, result.output.lines().count { it.startsWith("[stack-augmentor] DEBUG agent: configuration ") }, result.output)
        assertEquals(1, result.output.lines().count { it.startsWith("\tat com.hafnium.it.fixtures.JavaFixture{key=java-1}.run{arg0=42}(") }, result.output)
    }

    @Test
    fun `the live stack hint about objects the JIT optimized away is left out when that optimization is off`(@TempDir dir: Path) {
        assumeTrue(AgentMode.liveStack)
        val result = run(config(dir, "debug = true\n[augment.exceptions]\n\"**\" = true\n[augment.annotations]\n\"com.hafnium.it.fixtures.**\" = true\n"), "-XX:-EliminateAllocations")
        assertEquals(0, result.exitCode, result.output)
        assertTrue(result.output.contains("[stack-augmentor] DEBUG agent: reading frames from the live stack"), result.output)
        assertFalse(result.output.contains("-XX:-EliminateAllocations avoids that"), result.output)
        assertTrue(result.output.contains("at com.hafnium.it.fixtures.JavaFixture{key=java-1}.run{arg0=42}(JavaFixture.java:"), result.output)
    }

    @Test
    fun `without a configuration the built-in one augments the annotated members and parameters`() {
        val result = run(null)
        assertEquals(0, result.exitCode, result.output)
        record("JavaMain, with the agent and no configuration", result.output)
        assertFalse(result.output.contains("[stack-augmentor] WARN"), result.output)
        assertTrue(result.output.contains("at com.hafnium.it.fixtures.JavaFixture{key=java-1}.run{arg0=42}(JavaFixture.java:"), result.output)
        assertNoKotlin(result.output)
    }

    @Test
    fun `a configuration without entries augments nothing`(@TempDir dir: Path) {
        // The built-in configuration, which applies after the file, would read the annotations of all classes.
        val result = run(config(dir, "debug = false\n[augment.annotations]\n\"**\" = false\n"))
        assertEquals(0, result.exitCode, result.output)
        assertTrue(
            result.output.contains(
                "[stack-augmentor] WARN agent: the configuration has no [augment.receiver] or [augment.params] entries other than " +
                    "\"-\" and no [augment.annotations] entry that is true, so nothing will be augmented",
            ),
            result.output,
        )
        assertTrue(result.output.contains("at com.hafnium.it.fixtures.JavaFixture.run(JavaFixture.java:"), result.output)
        assertNoKotlin(result.output)
    }

    @Test
    fun `invalid configuration is reported without the Kotlin runtime`(@TempDir dir: Path) {
        val result = run(config(dir, "debug = false\n[augment]\nmaxValueLength = 1\n"))
        assertNotEquals(0, result.exitCode, result.output)
        assertTrue(
            result.output.contains("[stack-augmentor] ERROR agent: stack-augmentor.toml, line 3: maxValueLength must be between 2 and 10000, was 1"),
            result.output,
        )
        assertNoKotlin(result.output)
    }

    companion object {
        @JvmField
        @RegisterExtension
        val report = FrameReport("Java application without Kotlin", basePackage = "com.hafnium.it.fixtures")
    }
}
