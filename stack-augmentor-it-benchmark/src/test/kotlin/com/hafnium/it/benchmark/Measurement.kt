package com.hafnium.it.benchmark

/**
 * Microseconds per run of a measured action: the median of the batches, and their range. [batch] runs in each of
 * [batches] batches.
 */
data class Measurement(val median: Double, val min: Double, val max: Double, val batch: Int, val batches: Int) {

    companion object {
        private val warmupMillis = System.getProperty("benchmark.warmupMillis", "2000").toLong()
        /**
         * The warm-up of every later measurement in the JVM: all cases run the same code, which the first one's
         * warm-up has compiled, so the later ones only need the JIT to adapt to their case.
         */
        private val laterWarmupMillis = System.getProperty("benchmark.laterWarmupMillis", "300").toLong()
        private var warmedUp = false
        private val batchMillis = System.getProperty("benchmark.batchMillis", "500").toLong()
        private val batches = System.getProperty("benchmark.batches", "10").toInt()

        /** Runs the action until it is warmed up, then in batches of about the same time each. */
        fun of(action: () -> Unit): Measurement {
            val warmupEnd = System.nanoTime() + (if (warmedUp) laterWarmupMillis else warmupMillis) * 1_000_000
            warmedUp = true
            while (System.nanoTime() < warmupEnd) {
                action()
            }
            val start = System.nanoTime()
            repeat(10) { action() }
            val nanosPerRun = (System.nanoTime() - start) / 10.0
            val batch = maxOf(1, (batchMillis * 1_000_000 / nanosPerRun).toInt())
            val micros = DoubleArray(batches) {
                val batchStart = System.nanoTime()
                repeat(batch) { action() }
                (System.nanoTime() - batchStart) / 1000.0 / batch
            }.sorted()
            // With an even number of batches, the mean of the two middle ones.
            val median = (micros[(micros.size - 1) / 2] + micros[micros.size / 2]) / 2
            return Measurement(median, micros.first(), micros.last(), batch, batches)
        }
    }
}
