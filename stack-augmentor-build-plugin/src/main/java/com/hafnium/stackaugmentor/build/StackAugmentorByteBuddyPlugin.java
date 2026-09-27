package com.hafnium.stackaugmentor.build;

import com.hafnium.stackaugmentor.instrument.ExitAdviceFactory;
import com.hafnium.stackaugmentor.instrument.IdParameters;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.runtime.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.Log;
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
        this(new AugmentorConfig());
    }

    /** @param configFile a TOML configuration; its {@code [augment.receiver]} and {@code [augment.params]} apply. */
    public StackAugmentorByteBuddyPlugin(String configFile) {
        this(AugmentorConfig.load(Path.of(configFile)));
    }

    private StackAugmentorByteBuddyPlugin(AugmentorConfig config) {
        IdParameters parameters = new IdParameters(config);
        this.matching = new TypeMatching(config, parameters);
        this.advice = ExitAdviceFactory.create(parameters);
        Log.setDebug(config.debug());
        if (!config.hasAugmentEntries()) {
            Log.warn("build plugin: the configuration has no [augment.receiver] or [augment.params] entries, "
                    + "so nothing will be augmented");
        }
        Log.debug(() -> "build plugin, [augment.receiver]: " + config.classesDescription()
                + ", [augment.params]: " + config.methodsDescription());
    }

    @Override
    public boolean matches(TypeDescription target) {
        return matching.instrument(target);
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
