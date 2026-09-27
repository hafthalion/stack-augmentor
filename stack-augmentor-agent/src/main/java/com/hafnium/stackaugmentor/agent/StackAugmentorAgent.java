package com.hafnium.stackaugmentor.agent;

import com.hafnium.stackaugmentor.runtime.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.FrameFormat;
import com.hafnium.stackaugmentor.runtime.Log;

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

    static final String NOTHING_CONFIGURED =
            "the configuration has no [augment.receiver] or [augment.params] entries, so nothing will be augmented";

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
        AugmentorConfig config = AugmentorConfig.load(agentArgs);
        FrameFormat format = FrameFormat.create(config);
        Log.setDebug(config.debug());
        logConfiguration(agentArgs, config);
        if (!config.hasAugmentEntries()) {
            Log.warn(NOTHING_CONFIGURED);
        }
        BridgeInjector.inject(instrumentation);
        Installer.install(instrumentation, config, format);
    }

    private static void logConfiguration(String agentArgs, AugmentorConfig config) {
        if (!Log.isDebug()) {
            return;
        }
        String location = AugmentorConfig.location(agentArgs);
        Log.debug(() -> "configuration: " + (location != null ? location : "none, using the defaults"));
        Log.debug(() -> "[augment.receiver]: " + config.classesDescription());
        Log.debug(() -> "[augment.params]: " + config.methodsDescription());
        Log.debug(() -> "format: " + config.frameFormat() + " / " + config.receiverFormat() + " / " + config.paramsFormat()
                + ", maxIdLength=" + config.maxIdLength() + ", maxParams=" + config.maxParams());
    }
}
