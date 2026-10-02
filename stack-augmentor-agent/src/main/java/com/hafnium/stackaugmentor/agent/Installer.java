package com.hafnium.stackaugmentor.agent;

import com.hafnium.stackaugmentor.agent.instrument.ClassInstrumentation;
import com.hafnium.stackaugmentor.agent.live.LiveStack;
import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.handler.StackTraces;
import com.hafnium.stackaugmentor.runtime.ids.FrameFormat;
import net.bytebuddy.dynamic.Nexus;
import net.bytebuddy.dynamic.loading.ClassInjector;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Chooses the agent's mode: the experimental live-stack mode ({@link LiveStack}) if the native library was loaded and it
 * can be used, otherwise instrumenting the configured classes ({@link ClassInstrumentation}).
 */
final class Installer {

    private Installer() {
    }

    static void install(Instrumentation instrumentation, AugmentorConfig config, FrameFormat format) {
        // Inlined advice needs neither the Nexus nor Unsafe-based class injection; turning them off avoids
        // the JDK's sun.misc.Unsafe warnings. In the shaded jar these property names are relocated, so they
        // do not affect a ByteBuddy copy used by the application itself.
        System.setProperty(Nexus.PROPERTY, "true");
        System.setProperty(ClassInjector.UsingUnsafe.SAFE_PROPERTY, "true");

        boolean javaLangOpen = openJavaBase(instrumentation);
        if (LiveStack.requested()) {
            String problem = javaLangOpen ? LiveStack.install(instrumentation, config, format) : "java.lang cannot be opened to the agent";
            if (problem == null) {
                return;
            }
            Log.warn("agent: cannot read frames from the live stack, so classes are instrumented instead: " + problem);
        }
        ClassInstrumentation.install(instrumentation, config, format, javaLangOpen);
    }

    /**
     * Opens {@code java.lang} to the runtime and agent classes: the handler can then replace a frame in the throwable's
     * own stack trace instead of copying the whole trace twice for every frame, and the live-stack mode can read live
     * stack frames. Also lets {@code java.base} read the bridge, for the code the live-stack mode adds to
     * {@link Throwable}.
     */
    private static boolean openJavaBase(Instrumentation instrumentation) {
        Module javaBase = Throwable.class.getModule();
        Set<Module> agent = Stream.of(StackTraces.class.getModule(), Installer.class.getModule()).collect(Collectors.toSet());
        try {
            if (!instrumentation.isModifiableModule(javaBase)) {
                return false;
            }
            instrumentation.redefineModule(javaBase, Set.of(Dispatch.class.getModule()), Map.of(), Map.of("java.lang", agent), Set.of(),
                    Map.of());
            return true;
        } catch (RuntimeException e) {
            Log.debug(() -> "agent: java.lang cannot be opened, so stack traces are copied for every frame: " + e);
            return false;
        }
    }

}
