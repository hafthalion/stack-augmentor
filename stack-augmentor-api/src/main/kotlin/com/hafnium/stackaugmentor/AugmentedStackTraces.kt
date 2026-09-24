package com.hafnium.stackaugmentor

import com.hafnium.stackaugmentor.bridge.FrameIdRegistry
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Prints stack traces in the `printStackTrace` layout, using the ids the agent recorded in registry mode.
 * Without the agent, or in rewrite mode, the output is the same as [Throwable.printStackTrace].
 */
object AugmentedStackTraces {

    private val registryAvailable: Boolean by lazy {
        try {
            Class.forName("com.hafnium.stackaugmentor.bridge.FrameIdRegistry", false, null)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    @JvmStatic
    fun format(throwable: Throwable): String = buildString { appendTo(this, throwable) }

    @JvmStatic
    fun appendTo(out: Appendable, throwable: Throwable) {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        seen.add(throwable)
        out.append(throwable.toString()).append(LINE_SEPARATOR)
        val trace = throwable.stackTrace
        val frames = framesOf(throwable)
        for (i in trace.indices) {
            out.append("\tat ").append(frameText(trace, frames, i)).append(LINE_SEPARATOR)
        }
        for (suppressed in throwable.suppressed) {
            appendEnclosed(out, suppressed, trace, SUPPRESSED_CAPTION, "\t", seen)
        }
        throwable.cause?.let { appendEnclosed(out, it, trace, CAUSE_CAPTION, "", seen) }
    }

    private fun appendEnclosed(
        out: Appendable,
        throwable: Throwable,
        enclosingTrace: Array<StackTraceElement>,
        caption: String,
        prefix: String,
        seen: MutableSet<Throwable>,
    ) {
        if (!seen.add(throwable)) {
            out.append(prefix).append(caption).append("[CIRCULAR REFERENCE: ").append(throwable.toString()).append("]")
                .append(LINE_SEPARATOR)
            return
        }
        val trace = throwable.stackTrace
        val frames = framesOf(throwable)
        var m = trace.size - 1
        var n = enclosingTrace.size - 1
        while (m >= 0 && n >= 0 && trace[m] == enclosingTrace[n]) {
            m--
            n--
        }
        val framesInCommon = trace.size - 1 - m
        out.append(prefix).append(caption).append(throwable.toString()).append(LINE_SEPARATOR)
        for (i in 0..m) {
            out.append(prefix).append("\tat ").append(frameText(trace, frames, i)).append(LINE_SEPARATOR)
        }
        if (framesInCommon != 0) {
            out.append(prefix).append("\t... ").append(framesInCommon.toString()).append(" more").append(LINE_SEPARATOR)
        }
        for (suppressed in throwable.suppressed) {
            appendEnclosed(out, suppressed, trace, SUPPRESSED_CAPTION, prefix + "\t", seen)
        }
        throwable.cause?.let { appendEnclosed(out, it, trace, CAUSE_CAPTION, prefix, seen) }
    }

    private fun framesOf(throwable: Throwable): Array<String?>? =
        if (registryAvailable) FrameIdRegistry.frames(throwable) else null

    private fun frameText(trace: Array<StackTraceElement>, frames: Array<String?>?, index: Int): String =
        frames?.takeIf { it.size == trace.size }?.get(index) ?: trace[index].toString()

    private const val CAUSE_CAPTION = "Caused by: "
    private const val SUPPRESSED_CAPTION = "Suppressed: "
    private val LINE_SEPARATOR = System.lineSeparator()
}
