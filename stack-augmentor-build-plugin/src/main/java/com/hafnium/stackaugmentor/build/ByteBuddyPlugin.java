package com.hafnium.stackaugmentor.build;

import com.hafnium.stackaugmentor.instrument.ParamSelection;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.instrument.advice.ConstructorExit;
import com.hafnium.stackaugmentor.instrument.advice.ExitAdviceFactory;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.Startup;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;

import java.nio.file.Path;
import java.util.List;

/**
 * Adds the exit advice to the compiled classes of a project at build time, so no Java agent is needed.
 * Only the project's classes are changed; libraries are left alone.
 * At runtime, the application needs {@code stack-augmentor-runtime} on its classpath.
 *
 * <p>Argument 0 is the path of a global TOML configuration, which applies before the built-in one
 * ({@link AugmentorConfig#layered}): their {@code [augment.annotations]}, {@code [augment.receiver]} and
 * {@code [augment.params]} entries decide which receiver members and parameter values are shown, and the classes and
 * methods that need the advice for that are instrumented, as with the agent; {@code debug} logs what gets instrumented.
 * Without it (e.g. when discovered through {@code META-INF/net.bytebuddy/build.plugins}), the built-in configuration
 * applies alone ({@link AugmentorConfig#BUILT_IN_RESOURCE}): the methods that {@code @Augmented} selects are
 * instrumented.
 */
public final class ByteBuddyPlugin implements Plugin {

    private final ParamSelection parameters;
    private final TypeMatching matching;
    private final Advice advice;

    public ByteBuddyPlugin() {
        this(AugmentorConfig.builtIn(), AugmentorConfig.BUILT_IN_LOCATION);
    }

    /** @param configFile a TOML configuration; its {@code [augment.receiver]} and {@code [augment.params]} apply. */
    public ByteBuddyPlugin(String configFile) {
        // The ByteBuddy Gradle plugin reports a failing constructor without its cause, so Startup prints the reason too.
        this(Startup.load(Startup.BUILD_PLUGIN, () -> AugmentorConfig.load(Path.of(configFile))), configFile);
    }

    private ByteBuddyPlugin(AugmentorConfig file, String location) {
        Startup.configure(Startup.BUILD_PLUGIN, location, file);
        AugmentorConfig config = AugmentorConfig.layered(file == AugmentorConfig.builtIn() ? List.of(file) : List.of(file, AugmentorConfig.builtIn()));
        Startup.warnIfNoExceptions(Startup.BUILD_PLUGIN, config);
        Startup.warnIfNothingConfigured(Startup.BUILD_PLUGIN, config);
        this.parameters = new ParamSelection(config);
        this.matching = new TypeMatching(config, parameters);
        this.advice = ExitAdviceFactory.create(matching, parameters);
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
        return builder.visit(advice.on(methods)).visit(ConstructorExit.on(parameters, matching.constructors(typeDescription)));
    }

    @Override
    public void close() {
    }
}
