package com.hafnium.it.benchmark

/**
 * Where in a stack of [frames] frames the exception is caught, and which frames show ids. Depths count from 1 at the
 * top of the stack to [bottom]. The exception is always created at the bottom, in the deepest frame. With [causes], it is wrapped on its way up: every [wrapEvery] frames, a
 * frame catches it and throws a new exception with it as the cause, so the caught one has a chain of [causes] causes.
 * The stack is then shallower, so that the stack traces of the whole chain hold [FRAMES] frames together, as the single
 * exception's does.
 */
enum class Scenario(val configuredEvery: Int, val caughtNearTop: Boolean, val causes: Int = 0) {
    BOTTOM_CONFIGURED(4, false),
    BOTTOM_PLAIN(0, false),
    TOP_CONFIGURED(4, true),
    TOP_PLAIN(0, true),
    CAUSES_CONFIGURED(4, true, 3),
    CAUSES_PLAIN(0, true, 3);

    /** Where the exception is caught: near the bottom (3 frames above where it is created) or near the top. */
    val caught: String get() = when {
        causes > 0 -> "near the top, ${causes + 1} exceptions chain"
        caughtNearTop -> "near the top, single exception"
        else -> "near the bottom, single exception"
    }

    /**
     * How many frames apart the exceptions of the cause chain are created, or 0 without causes. Their stack traces hold
     * wrapEvery, 2 × wrapEvery, ... frames: [FRAMES] together.
     */
    val wrapEvery: Int get() = if (causes == 0) 0 else 2 * FRAMES / ((causes + 1) * (causes + 2))

    /** The depth of the stack. */
    val frames: Int get() = if (causes == 0) FRAMES else (causes + 1) * wrapEvery

    /** The depth of the deepest frame, which creates the exception. */
    val bottom: Int get() = frames

    /** The depth of the frame that creates the outermost exception, the one that is caught. */
    val outermostCreatedAt: Int get() = bottom - causes * wrapEvery

    /** Whether the frame at [depth] wraps the exception from below in a new one. */
    fun wrapsAt(depth: Int): Boolean = causes > 0 && depth < bottom && depth >= outermostCreatedAt && (bottom - depth) % wrapEvery == 0

    /** The depth of the frame that catches the exception: 3 frames above the bottom, or 2 below the top. */
    val catchAt: Int get() = if (caughtNearTop) 3 else bottom - 3

    /** The share of the frames that are configured, e.g. 25%. */
    val configuredPercent: Int get() = if (configuredEvery == 0) 0 else 100 / configuredEvery

    val title: String get() = "Caught $caught, ${if (configuredEvery == 0) "no frame" else "$configuredPercent% of the frames"} configured"

    companion object {
        /** The frames of all stack traces of a run together. */
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
