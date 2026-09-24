package com.hafnium.stackaugmentor.agent

import com.hafnium.stackaugmentor.bridge.Dispatch
import com.hafnium.stackaugmentor.bridge.WeakIdentityMap

/**
 * Called when an exception leaves an instrumented method: finds that method's frame in the
 * exception's stack trace and attaches the ids to it.
 */
class ThrowHandler(
    private val config: AugmentorConfig,
    private val resolver: IdResolver,
    private val format: FrameFormat,
) : Dispatch.Handler {

    /**
     * Per throwable, the index after the last frame handled. Frames unwind from the top of the trace
     * downwards, so the search continues from there; this keeps recursive calls apart.
     */
    private val cursors = WeakIdentityMap<Throwable, Int>()

    override fun onThrow(
        self: Any?,
        thrown: Throwable,
        owner: String,
        method: String,
        paramValues: Array<Any?>?,
        paramNames: Array<String>?,
    ) {
        val trace = thrown.stackTrace
        if (trace.isEmpty()) return
        val start = cursors.get(thrown) ?: 0
        val index = (start until trace.size).firstOrNull { trace[it].className == owner && trace[it].methodName == method }
            ?: return // created elsewhere, e.g. rethrown from another thread
        cursors.put(thrown, index + 1)

        val receiverId = self?.let { resolver.receiverId(it, allowFallback = config.isIncluded(owner)) }
        val paramIds = if (paramValues != null && paramNames != null) {
            paramNames.indices.map { NamedId(paramNames[it], resolver.paramId(paramValues[it])) }
        } else {
            emptyList()
        }
        if (receiverId == null && paramIds.isEmpty()) return

        trace[index] = format.rewrite(trace[index], receiverId, paramIds)
        thrown.stackTrace = trace // no effect if the throwable's stack trace is not writable
    }
}
