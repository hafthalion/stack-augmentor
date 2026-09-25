package com.hafnium.stackaugmentor.build

import com.hafnium.stackaugmentor.instrument.IdParameters
import com.hafnium.stackaugmentor.instrument.TypeMatching
import com.hafnium.stackaugmentor.instrument.exitAdvice
import com.hafnium.stackaugmentor.runtime.AugmentorConfig
import com.hafnium.stackaugmentor.runtime.Log
import net.bytebuddy.build.Plugin
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import java.nio.file.Path

/**
 * Adds the exit advice to the compiled classes of a project at build time, so no Java agent is needed.
 * Only classes that use `@StackTraceId` are changed; libraries are left alone.
 * At runtime, the application needs `stack-augmentor-runtime` on its classpath.
 *
 * Without arguments (e.g. when discovered through `META-INF/net.bytebuddy/build.plugins`), every class
 * using `@StackTraceId` is instrumented. With the path of a TOML configuration as argument 0, its
 * `[instrument] annotatedClasses` limits the packages, and `debug` logs what gets instrumented.
 */
class StackAugmentorPlugin private constructor(config: AugmentorConfig) : Plugin {

    constructor() : this(AugmentorConfig())

    /** @param configFile a TOML configuration; its `[instrument]` section applies. */
    constructor(configFile: String) : this(AugmentorConfig.load(Path.of(configFile)))

    private val parameters = IdParameters(config)
    private val matching = TypeMatching(config, parameters)
    private val advice = exitAdvice(parameters)

    init {
        Log.debug = config.debug
        val packages = config.annotatedClasses
        Log.debug { "build plugin, instrument.annotatedClasses: ${if (packages.isEmpty()) "all packages" else packages.toString()}" }
    }

    override fun matches(target: TypeDescription): Boolean = matching.instrument(target)

    override fun apply(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        classFileLocator: ClassFileLocator,
    ): DynamicType.Builder<*> {
        val methods = matching.methods(typeDescription)
        Log.debug { matching.describe(typeDescription, methods) }
        return builder.visit(advice.on(methods))
    }

    override fun close() {
    }
}
