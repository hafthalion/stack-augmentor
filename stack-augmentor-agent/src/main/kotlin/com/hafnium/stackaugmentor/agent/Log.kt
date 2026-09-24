package com.hafnium.stackaugmentor.agent

internal object Log {
    @Volatile
    var debug: Boolean = false

    fun warn(message: String) {
        System.err.println("[stack-augmentor] WARN $message")
    }

    fun debug(message: String) {
        if (debug) System.err.println("[stack-augmentor] $message")
    }
}
