package com.hafnium.it.benchmark

import com.hafnium.stackaugmentor.RootCauseFirst
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * Measures how long an exception takes in each [Scenario], in the JVM [Mode] that the Gradle task started, and writes
 * the result to `benchmark.results`, where the `benchmark` task's report reads it.
 */
class ExceptionCostBenchmark {

    private val mode = Mode.of(System.getProperty("benchmark.mode", Mode.PLAIN.id))
    private val results = System.getProperty("benchmark.results")?.let(::File)

    @ParameterizedTest(name = "{0}, logged: {1}")
    @MethodSource("cases")
    fun exception(scenario: Scenario, logged: Boolean) {
        val stack = Stack(scenario, logged)
        val measurement = onFreshThread {
            assertEquals(stack.catchAt - 1, stack.run(), "the exception is caught where the scenario says")
            Measurement.of { stack.run() }
        }
        if (logged) {
            val configured = stack.lastLog.lines().any { it.contains("Configured{") }
            assertEquals(scenario.configuredEvery > 0 && mode != Mode.PLAIN, configured, stack.lastLog.lines().take(3).joinToString("\n"))
        }
        assertTrue(measurement.median > 0)
        results?.let { write(it, scenario, logged, measurement, stack) }
    }

    private fun write(directory: File, scenario: Scenario, logged: Boolean, measurement: Measurement, stack: Stack) {
        directory.mkdirs()
        File(directory, "${mode.id}.tsv").appendText(
            listOf(scenario.name, logged, measurement.median, measurement.min, measurement.max, measurement.batch, measurement.batches).joinToString("\t") + "\n"
        )
        if (logged) {
            File(directory, "${mode.id}-${scenario.name}.log").writeText(stack.lastLog)
            // Root cause first, for the report's excerpt; not part of what is measured.
            File(directory, "${mode.id}-${scenario.name}.root-first.log").writeText(RootCauseFirst.format(stack.lastLogged!!))
        }
        if (mode == Mode.PLAIN) {
            File(directory, "environment.txt").writeText(
                "Java ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")}), " +
                    "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} cores"
            )
        }
    }

    /**
     * Runs [action] on a new thread and returns its result: its stack has only a few frames below the benchmark's, while
     * the test's own has about 150 of JUnit and Gradle, which every stack trace would hold, once per exception.
     */
    private fun <T> onFreshThread(action: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread { result = runCatching(action) }
        thread.start()
        thread.join()
        return result!!.getOrThrow()
    }

    companion object {
        @JvmStatic
        fun cases(): List<Arguments> = Scenario.entries.flatMap { listOf(Arguments.of(it, true), Arguments.of(it, false)) }
    }
}
