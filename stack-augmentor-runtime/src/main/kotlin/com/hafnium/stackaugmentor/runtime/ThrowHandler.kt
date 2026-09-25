package com.hafnium.stackaugmentor.runtime

import com.hafnium.stackaugmentor.instrument.bridge.Dispatch

/**
 * Called when an exception leaves an instrumented method: finds that method's frame in the
 * exception's stack trace and attaches the ids to it.
 *
 * The Java agent creates it with the agent's configuration and installs it. With build-time
 * instrumentation no agent does that: [Dispatch] creates it through `ServiceLoader` with the no-argument
 * constructor, which reads the configuration from `-Dstackaugmentor.config` or from `stack-augmentor.toml`
 * on the classpath. Only `[augment]` and `debug` matter then: what gets instrumented was decided at build time.
 */
class ThrowHandler(
    private val resolver: IdResolver,
    private val format: FrameFormat,
) : Dispatch.Handler {

    constructor(config: AugmentorConfig) : this(IdResolver(config), FrameFormat.create(config))

    /** For `ServiceLoader`: build-time instrumentation. */
    constructor() : this(runtimeConfig())

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

    companion object {
        const val CLASSPATH_CONFIG = "stack-augmentor.toml"

        /** The configuration for build-time instrumentation: the system property, then the classpath, then the defaults. */
        private fun runtimeConfig(): AugmentorConfig {
            val (config, source) = AugmentorConfig.location(null)?.let { AugmentorConfig.load(null) to it }
                ?: ThrowHandler::class.java.classLoader?.getResource(CLASSPATH_CONFIG)?.let { resource ->
                    val text = resource.openStream().use { String(it.readAllBytes(), Charsets.UTF_8) }
                    AugmentorConfig.parse(text, CLASSPATH_CONFIG) to resource.toString()
                }
                ?: (AugmentorConfig() to "none, using the defaults")
            Log.debug = config.debug
            Log.debug { "build-time instrumentation, configuration: $source" }
            return config
        }
    }
}
