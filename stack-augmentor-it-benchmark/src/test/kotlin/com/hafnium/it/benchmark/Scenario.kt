package com.hafnium.it.benchmark

/**
 * Where in a stack of [FRAMES] frames the exception is caught, and which frames show ids. The exception is always created
 * at the bottom, in the deepest frame.
 */
enum class Scenario(val configuredEvery: Int, val caughtNearTop: Boolean) {
    BOTTOM_CONFIGURED(4, false),
    BOTTOM_PLAIN(0, false),
    TOP_CONFIGURED(4, true),
    TOP_PLAIN(0, true);

    /** Where the exception is caught: near the bottom (3 frames above where it is created) or near the top. */
    val caught: String get() = if (caughtNearTop) "near the top" else "near the bottom"

    /** The share of the frames that are configured, e.g. 25%. */
    val configuredPercent: Int get() = if (configuredEvery == 0) 0 else 100 / configuredEvery

    val title: String get() = "Caught $caught, ${if (configuredEvery == 0) "no frame" else "$configuredPercent% of the frames"} configured"

    companion object {
        const val FRAMES = 1000
    }
}

/** How the JVM runs: one Gradle task each, in this order. */
enum class Mode(val title: String, val description: String) {
    PLAIN("No agent", "The plain Kotlin code without stack-augmentor: the baseline."),
    AGENT_IN_PLACE(
        "Agent, frames written in place",
        "The agent instruments the configured classes; when the exception leaves a configured method, its frame is " +
            "written into the exception's own stack trace (inPlaceModification = true).",
    ),
    AGENT_COPYING(
        "Agent, stack trace copied",
        "The same, but each frame is written by copying the whole stack trace and setting it again (the default).",
    ),
    LIVE(
        "Agent, live-stack mode",
        "PR #23's experimental mode: no method is instrumented; when an exception is created, the ids of the configured " +
            "frames are read from the live stack with the native library.",
    );

    val id: String get() = name.lowercase().split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }.replaceFirstChar(Char::lowercase)

    companion object {
        fun of(id: String): Mode = entries.first { it.id == id }
    }
}
