package com.hafnium.stackaugmentor.agent.instrument;

import com.hafnium.stackaugmentor.instrument.IdParameters;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.instrument.advice.ConstructorExit;
import com.hafnium.stackaugmentor.instrument.advice.ExitAdviceFactory;
import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.Startup;
import com.hafnium.stackaugmentor.runtime.handler.StackTraces;
import com.hafnium.stackaugmentor.runtime.handler.ThrowHandler;
import com.hafnium.stackaugmentor.runtime.ids.FrameFormat;
import com.hafnium.stackaugmentor.runtime.ids.IdResolver;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.isBootstrapClassLoader;

/**
 * The agent's usual mode: it adds the same code to the configured classes as the build plugin, and that code calls the
 * runtime's {@link ThrowHandler}.
 */
public final class ClassInstrumentation {

    private ClassInstrumentation() {
    }

    /** Installs the handler, and instruments the configured classes that are loaded now or later. */
    public static void install(Instrumentation instrumentation, AugmentorConfig config, FrameFormat format, boolean javaLangOpen) {
        StackTraces stackTraces = Startup.load(Startup.AGENT, () -> StackTraces.configured(config,
                javaLangOpen ? "but it is not" : "but the agent cannot open it"));
        Dispatch.install(new ThrowHandler(new IdResolver(config), format, stackTraces));

        IdParameters parameters = new IdParameters(config);
        TypeMatching matching = new TypeMatching(config, parameters);
        Advice advice = ExitAdviceFactory.create(parameters);

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                // The advice is inlined, so no helper classes need to be injected into class loaders.
                .with(AgentBuilder.InjectionStrategy.Disabled.INSTANCE)
                // Transformations are logged by the transformer below; ByteBuddy only reports classes it failed on.
                .with(config.debug()
                        ? AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly()
                        : AgentBuilder.Listener.NoOp.INSTANCE)
                .assureReadEdgeTo(instrumentation, Dispatch.class)
                // Classes instrumented at build time already call Dispatch.
                .with(BuildTimeClasses::new)
                // The same types are left alone as by the build plugin.
                .ignore(TypeMatching.IGNORED)
                .or(any(), isBootstrapClassLoader())
                .type(matching::instrument)
                .transform((builder, type, classLoader, module, protectionDomain) -> {
                    ElementMatcher<MethodDescription> methods = matching.methods(type);
                    if (Log.isDebug()) {
                        Log.debug(() -> matching.describe(type, methods));
                    }
                    return builder.visit(advice.on(methods)).visit(ConstructorExit.on(parameters, matching.constructors(type)));
                })
                .installOn(instrumentation);
    }

}
