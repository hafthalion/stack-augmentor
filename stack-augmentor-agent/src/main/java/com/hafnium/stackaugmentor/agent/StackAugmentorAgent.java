package com.hafnium.stackaugmentor.agent;

import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.ClassLoaderConfigs;
import com.hafnium.stackaugmentor.runtime.config.Startup;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent entry point: {@code -javaagent:stack-augmentor-agent.jar[=config=<path>]}.
 *
 * <p>This class must not touch the bridge classes: they only become loadable once {@link BridgeInjector}
 * has appended the bridge jar to the bootstrap class loader.
 */
public final class StackAugmentorAgent {

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private StackAugmentorAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        start(agentArgs, instrumentation);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        start(agentArgs, instrumentation);
    }

    private static void start(String agentArgs, Instrumentation instrumentation) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        // An invalid global configuration stops the JVM here, with the reason in the message.
        ClassLoaderConfigs<Configured> contexts = Startup.load(Startup.AGENT, () -> ClassLoaderConfigs.create(Startup.AGENT,
                AugmentorConfig.global(agentArgs), AugmentorConfig.location(agentArgs), Configured::of));
        Startup.warnIfNothingConfigured(Startup.AGENT, contexts.baseConfig());
        BridgeInjector.inject(instrumentation);
        Installer.install(instrumentation, contexts);
    }
}
