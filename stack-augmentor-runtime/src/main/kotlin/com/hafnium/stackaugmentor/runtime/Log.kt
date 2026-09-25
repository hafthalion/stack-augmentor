package com.hafnium.stackaugmentor.runtime

object Log {
    /** Set from the `debug` configuration key. */
    @Volatile
    var debug: Boolean = false

    /** Always printed; takes a lambda like [debug] so both are called the same way. */
    inline fun warn(message: () -> String) {
        System.err.println("[stack-augmentor] WARN ${message()}")
    }

    /** The message is only built when debugging is on; inlined, so a disabled call costs one field read. */
    inline fun debug(message: () -> String) {
        if (debug) System.err.println("[stack-augmentor] DEBUG ${message()}")
    }
}
