package com.hafnium.stackaugmentor.agent;

import com.hafnium.stackaugmentor.runtime.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.FrameFormat;
import com.hafnium.stackaugmentor.runtime.IdSpec;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.ParamRef;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
        AugmentorConfig config = AugmentorConfig.load(agentArgs);
        FrameFormat format = FrameFormat.create(config);
        Log.setDebug(config.debug());
        logConfiguration(agentArgs, config);
        BridgeInjector.inject(instrumentation);
        Installer.install(instrumentation, config, format);
    }

    private static void logConfiguration(String agentArgs, AugmentorConfig config) {
        if (!Log.isDebug()) {
            return;
        }
        String location = AugmentorConfig.location(agentArgs);
        Log.debug(() -> "configuration: " + (location != null ? location : "none, using the defaults"));
        List<String> packages = config.annotatedClasses();
        Log.debug(() -> "instrument.annotatedClasses: " + (packages.isEmpty() ? "all packages" : packages.toString()));
        String classIds = config.ids().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + describe(entry.getValue()))
                .collect(Collectors.joining(", "));
        Log.debug(() -> "[instrument.classIds]: " + (classIds.isEmpty() ? "none" : classIds));
        String methodParams = config.params().entrySet().stream()
                .map(entry -> entry.getKey() + entry.getValue().stream()
                        .map(StackAugmentorAgent::describe)
                        .collect(Collectors.joining(", ", "[", "]")))
                .collect(Collectors.joining(", "));
        Log.debug(() -> "[instrument.methodParams]: " + (methodParams.isEmpty() ? "none" : methodParams));
        Log.debug(() -> "format: " + config.frameFormat() + " / " + config.receiverFormat() + " / " + config.paramsFormat()
                + ", maxIdLength=" + config.maxIdLength() + ", maxParams=" + config.maxParams());
    }

    private static String describe(IdSpec spec) {
        return spec instanceof IdSpec.MethodSpec ? spec.memberName() + "()" : spec.memberName();
    }

    private static String describe(ParamRef ref) {
        return switch (ref) {
            case ParamRef.ByName byName -> byName.name();
            case ParamRef.ByIndex byIndex -> "#" + byIndex.index();
            case ParamRef.All all -> "*";
        };
    }
}
