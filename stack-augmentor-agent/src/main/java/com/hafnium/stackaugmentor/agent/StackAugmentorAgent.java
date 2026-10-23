package com.hafnium.stackaugmentor.agent;

import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.ClassLoaderConfigs;
import com.hafnium.stackaugmentor.runtime.config.ConfigException;
import com.hafnium.stackaugmentor.runtime.config.Startup;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent entry point: {@code -javaagent:stack-augmentor-agent.jar}, without arguments: the JVM's configuration file
 * is named by {@code -Dstackaugmentor.config=<path>}.
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
        // Also for an agent given twice: arguments are a mistake wherever they are.
        if (agentArgs != null && !agentArgs.isBlank()) {
            Startup.load(Startup.AGENT, () -> {
                throw new ConfigException("The agent takes no arguments, was '" + agentArgs + "': name the configuration file with -D"
                        + AugmentorConfig.CONFIG_PROPERTY + "=<file>");
            });
        }
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        // An invalid configuration of the JVM or of the system class loader stops the JVM here, with the reason.
        ClassLoaderConfigs<Configured> contexts = Startup.load(Startup.AGENT, () -> ClassLoaderConfigs.create(Startup.AGENT,
                AugmentorConfig.global(), AugmentorConfig.location(), Configured::of));
        BridgeInjector.inject(instrumentation);
        Installer.install(instrumentation, contexts);
    }
}
