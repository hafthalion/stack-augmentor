package com.hafnium

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs [JavaOrder], instrumented at build time, in a JVM of its own whose classpath has only the Java classes,
 * the resources, `stack-augmentor-api` and `stack-augmentor-runtime` with their dependencies; see build.gradle.kts.
 */
class WithoutKotlinTest {

    private val classpath = System.getProperty("stackaugmentor.example.javaClasspath")

    @Test
    fun `the classpath has neither Kotlin nor ByteBuddy`() {
        val jars = classpath.split(File.pathSeparator).map { File(it).name }
        assertTrue(jars.none { it.contains("kotlin", ignoreCase = true) }, classpath)
        assertTrue(jars.none { it.contains("byte-buddy", ignoreCase = true) }, classpath)
        assertTrue(jars.any { it.startsWith("stack-augmentor-runtime") }, classpath)
    }

    @Test
    fun `a Java class instrumented at build time runs without the Kotlin runtime`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(java, "-cp", classpath, "com.hafnium.JavaOrder")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the application did not finish")
        assertEquals(0, process.exitValue(), output)
        assertTrue(output.contains("at com.hafnium.JavaOrder{orderId=o-17}.ship{warehouse=north}(JavaOrder.java:"), "$output\nclasspath: $classpath")
        assertFalse(output.contains("kotlin/"), output)
        assertFalse(output.contains("NoClassDefFoundError"), output)
    }
}
