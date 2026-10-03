package com.hafnium.it.benchmark

/** Microseconds per run of a measured action: the median of the batches, and their range. */
data class Measurement(val median: Double, val min: Double, val max: Double, val batch: Int) {

    companion object {
        private val warmupMillis = System.getProperty("benchmark.warmupMillis", "2000").toLong()
        private val batchMillis = System.getProperty("benchmark.batchMillis", "250").toLong()
        private val batches = System.getProperty("benchmark.batches", "9").toInt()

        /** Runs the action until it is warmed up, then in batches of about the same time each. */
        fun of(action: () -> Unit): Measurement {
            val warmupEnd = System.nanoTime() + warmupMillis * 1_000_000
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
            return Measurement(micros[micros.size / 2], micros.first(), micros.last(), batch)
        }
    }
}
