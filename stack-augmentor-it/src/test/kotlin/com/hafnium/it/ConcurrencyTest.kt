package com.hafnium.it

import com.hafnium.it.fixtures.Diver
import com.hafnium.it.report.FrameReport
import com.hafnium.it.report.ReceiverEntries
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Many threads throw at once, each through receivers and parameters of its own, so ids must not leak between threads.
 * Half the calls park before throwing: a virtual thread then continues on another carrier thread. Enough calls run for
 * the JIT to compile [Diver.dive].
 */
class ConcurrencyTest {

    @Test
    fun `ids stay with their thread on virtual threads`() {
        val first = Executors.newVirtualThreadPerTaskExecutor().use { divers(it) }
        report.record("Diver(\"diver-0\").dive(20, park = true) on a virtual thread", first)
    }

    @Test
    fun `ids stay with their thread on platform threads`() {
        val first = Executors.newFixedThreadPool(8).use { divers(it) }
        report.record("Diver(\"diver-0\").dive(20, park = true) on a platform thread", first)
    }

    /** Runs the calls on [executor], checks the frames of each and returns the first exception. */
    private fun divers(executor: ExecutorService): Throwable {
        val calls = (0 until CALLS).map { n ->
            executor.submit<Throwable> {
                val name = "diver-$n"
                val depth = 20 + n % 5
                val park = n % 2 == 0
                val e = runCatching { Diver(name).dive(depth, park) }.exceptionOrNull()!!
                val expected = (0..depth).map { "com.hafnium.it.fixtures.Diver{name=$name}.dive{depth=$it, park=$park}" }
                assertEquals(expected, e.stackTrace.take(depth + 1).map { "${it.className}.${it.methodName}" }, name)
                e
            }
        }
        return calls.map { it.get() }.first()
    }

    companion object {
        private const val CALLS = 10_000

        @JvmField
        @RegisterExtension
        val report = FrameReport(
            "Concurrency",
            basePackage = "com.hafnium.it.fixtures",
            ownPackage = "com.hafnium.it",
            entries = ReceiverEntries.load(Path.of("src/test/resources/stack-augmentor.toml")),
        )
    }
}
