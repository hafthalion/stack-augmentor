package com.hafnium.stackaugmentor.runtime

import com.hafnium.stackaugmentor.bridge.Dispatch

/**
 * The handler for build-time instrumentation, where no agent installs one: [Dispatch] finds it through
 * `ServiceLoader` when the first exception leaves an instrumented method.
 *
 * The configuration is read from the file named by `-Dstackaugmentor.config`, or from
 * `stack-augmentor.toml` on the classpath; without either, the defaults apply. Only `[augment]` and
 * `debug` matter here: what gets instrumented (`[instrument]`) was decided at build time.
 */
class DefaultHandler : Dispatch.Handler {

    private val delegate: Dispatch.Handler

    init {
        val (config, source) = loadConfig()
        Log.debug = config.debug
        Log.debug { "build-time instrumentation, configuration: $source" }
        delegate = ThrowHandler(IdResolver(config), FrameFormat.create(config))
    }

    override fun onThrow(
        self: Any?,
        thrown: Throwable,
        owner: String,
        method: String,
        paramValues: Array<Any?>?,
        paramNames: Array<String>?,
    ) = delegate.onThrow(self, thrown, owner, method, paramValues, paramNames)

    private fun loadConfig(): Pair<AugmentorConfig, String> {
        AugmentorConfig.location(null)?.let { return AugmentorConfig.load(null) to it }
        val resource = DefaultHandler::class.java.classLoader?.getResource(CLASSPATH_CONFIG)
            ?: return AugmentorConfig() to "none, using the defaults"
        val text = resource.openStream().use { String(it.readAllBytes(), Charsets.UTF_8) }
        return AugmentorConfig.parse(text, CLASSPATH_CONFIG) to resource.toString()
    }

    companion object {
        const val CLASSPATH_CONFIG = "stack-augmentor.toml"
    }
}
