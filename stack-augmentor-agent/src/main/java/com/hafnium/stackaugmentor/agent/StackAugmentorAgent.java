package com.hafnium.stackaugmentor.agent;

import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.Startup;
import com.hafnium.stackaugmentor.runtime.ids.FrameFormat;

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
        // Invalid configuration stops the JVM here, with the reason in the message.
        AugmentorConfig config = Startup.load(Startup.AGENT, () -> AugmentorConfig.load(agentArgs));
        FrameFormat format = FrameFormat.create(config);
        String location = AugmentorConfig.location(agentArgs);
        Startup.configure(Startup.AGENT, location != null ? location : "none, using the defaults", config);
        Startup.warnIfNothingConfigured(Startup.AGENT, config);
        BridgeInjector.inject(instrumentation);
        Installer.install(instrumentation, config, format);
    }
}
