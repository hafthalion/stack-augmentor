package com.hafnium.stackaugmentor.agent

import com.hafnium.stackaugmentor.instrument.bridge.Dispatch
import com.hafnium.stackaugmentor.instrument.IdParameters
import com.hafnium.stackaugmentor.instrument.TypeMatching
import com.hafnium.stackaugmentor.instrument.exitAdvice
import com.hafnium.stackaugmentor.runtime.AugmentorConfig
import com.hafnium.stackaugmentor.runtime.FrameFormat
import com.hafnium.stackaugmentor.runtime.IdResolver
import com.hafnium.stackaugmentor.runtime.Log
import com.hafnium.stackaugmentor.runtime.ThrowHandler
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.Nexus
import net.bytebuddy.dynamic.loading.ClassInjector
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.any
import net.bytebuddy.matcher.ElementMatchers.isBootstrapClassLoader
import net.bytebuddy.matcher.ElementMatchers.isSynthetic
import net.bytebuddy.matcher.ElementMatchers.nameStartsWith
import java.lang.instrument.Instrumentation

internal object Installer {

    // Written without the trailing dot, so the shadow jar's relocation leaves them alone.
    private val IGNORED_PACKAGES = listOf(
        "java", "javax", "jdk", "sun", "com.sun", "kotlin", "net.bytebuddy", "com.hafnium.stackaugmentor",
    )

    fun install(instrumentation: Instrumentation, config: AugmentorConfig, format: FrameFormat) {
        Dispatch.install(ThrowHandler(IdResolver(config), format))

        // Inlined advice needs neither the Nexus nor Unsafe-based class injection; turning them off avoids
        // the JDK's sun.misc.Unsafe warnings. In the shaded jar these property names are relocated, so they
        // do not affect a ByteBuddy copy used by the application itself.
        System.setProperty(Nexus.PROPERTY, "true")
        System.setProperty(ClassInjector.UsingUnsafe.SAFE_PROPERTY, "true")

        val parameters = IdParameters(config)
        val matching = TypeMatching(config, parameters)
        val advice = exitAdvice(parameters)

        AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.TypeStrategy.Default.DECORATE)
            // The advice is inlined, so no helper classes need to be injected into class loaders.
            .with(AgentBuilder.InjectionStrategy.Disabled.INSTANCE)
            // Transformations are logged by the transformer below; ByteBuddy only reports classes it failed on.
            .with(
                if (config.debug) {
                    AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly()
                } else {
                    AgentBuilder.Listener.NoOp.INSTANCE
                },
            )
            .assureReadEdgeTo(instrumentation, Dispatch::class.java)
            .ignore(IGNORED_PACKAGES.fold(isSynthetic<TypeDescription>()) { matcher, prefix -> matcher.or(nameStartsWith("$prefix.")) })
            .or(any(), isBootstrapClassLoader())
            .type(ElementMatcher { type -> matching.instrument(type) })
            .transform { builder, type, _, _, _ ->
                val methods = matching.methods(type)
                Log.debug { matching.describe(type, methods) }
                builder.visit(advice.on(methods))
            }
            .installOn(instrumentation)
    }
}
