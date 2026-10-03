package com.hafnium.it.benchmark

/** Where in a stack of [FRAMES] frames the exception is caught, and which frames show ids. */
enum class Scenario(val title: String, val configuredEvery: Int, val caughtNearTop: Boolean) {
    BOTTOM_CONFIGURED("Created and caught near the bottom, 20% of the frames configured", 5, false),
    BOTTOM_PLAIN("Created and caught near the bottom, no frame configured", 0, false),
    TOP_CONFIGURED("Created at the bottom, caught near the top, 20% of the frames configured", 5, true),
    TOP_PLAIN("Created at the bottom, caught near the top, no frame configured", 0, true);

    companion object {
        const val FRAMES = 1000
    }
}

/** How the JVM runs: one Gradle task each, in this order. */
enum class Mode(val title: String) {
    PLAIN("No agent"),
    AGENT_IN_PLACE("Agent, frames written in place"),
    AGENT_COPYING("Agent, stack trace copied"),
    LIVE("Agent, live-stack mode");

    val id: String get() = name.lowercase().split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }.replaceFirstChar(Char::lowercase)

    companion object {
        fun of(id: String): Mode = entries.first { it.id == id }
    }
}
