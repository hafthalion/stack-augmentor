package com.hafnium.stackaugmentor.agent

import com.hafnium.stackaugmentor.bridge.Dispatch

/**
 * Called when an exception leaves an instrumented method: finds that method's frame in the
 * exception's stack trace and attaches the ids to it.
 */
class ThrowHandler(
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
        val start = cursors[thrown] ?: 0
        val index = (start until trace.size).firstOrNull { trace[it].className == owner && trace[it].methodName == method }
        if (index == null) {
            // Created elsewhere, e.g. stored and rethrown later, or rethrown from another thread.
            Log.debug { "$owner.$method: frame not found in the stack trace of ${thrown.javaClass.name}, which was created elsewhere" }
            return
        }
        cursors[thrown] = index + 1

        val receiverId = self?.let { resolver.receiverId(it) }
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
