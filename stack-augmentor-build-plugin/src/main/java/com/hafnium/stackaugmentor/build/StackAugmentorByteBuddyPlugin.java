package com.hafnium.stackaugmentor.build;

import com.hafnium.stackaugmentor.instrument.ExitAdviceFactory;
import com.hafnium.stackaugmentor.instrument.IdParameters;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.runtime.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.Startup;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;

import java.nio.file.Path;

/**
 * Adds the exit advice to the compiled classes of a project at build time, so no Java agent is needed.
 * Only the project's classes are changed; libraries are left alone.
 * At runtime, the application needs {@code stack-augmentor-runtime} on its classpath.
 *
 * <p>Argument 0 is the path of a TOML configuration: its {@code [augment.receiver]} and {@code [augment.params]}
 * entries decide which receivers and parameters get ids, and the classes and methods that need the advice for that
 * are instrumented, as with the agent; {@code debug} logs what gets instrumented. Without
 * it (e.g. when discovered through {@code META-INF/net.bytebuddy/build.plugins}), nothing is instrumented.
 */
public final class StackAugmentorByteBuddyPlugin implements Plugin {

    private final TypeMatching matching;
    private final Advice advice;

    public StackAugmentorByteBuddyPlugin() {
        this(new AugmentorConfig(), "none, using the defaults");
    }

    /** @param configFile a TOML configuration; its {@code [augment.receiver]} and {@code [augment.params]} apply. */
    public StackAugmentorByteBuddyPlugin(String configFile) {
        // The ByteBuddy Gradle plugin reports a failing constructor without its cause, so Startup prints the reason too.
        this(Startup.load(Startup.BUILD_PLUGIN, () -> AugmentorConfig.load(Path.of(configFile))), configFile);
    }

    private StackAugmentorByteBuddyPlugin(AugmentorConfig config, String location) {
        Startup.configure(Startup.BUILD_PLUGIN, location, config);
        Startup.warnIfNothingConfigured(Startup.BUILD_PLUGIN, config);
        IdParameters parameters = new IdParameters(config);
        this.matching = new TypeMatching(config, parameters);
        this.advice = ExitAdviceFactory.create(parameters);
    }

    @Override
    public boolean matches(TypeDescription target) {
        // The same types are left alone as by the agent.
        return !TypeMatching.IGNORED.matches(target) && matching.instrument(target);
    }

    @Override
    public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder,
                                        TypeDescription typeDescription,
                                        ClassFileLocator classFileLocator) {
        ElementMatcher<MethodDescription> methods = matching.methods(typeDescription);
        if (Log.isDebug()) {
            Log.debug(() -> matching.describe(typeDescription, methods));
        }
        return builder.visit(advice.on(methods));
    }

    @Override
    public void close() {
    }
}
