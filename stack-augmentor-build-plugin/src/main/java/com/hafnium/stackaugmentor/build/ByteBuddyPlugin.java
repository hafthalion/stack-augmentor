package com.hafnium.stackaugmentor.build;

import com.hafnium.stackaugmentor.instrument.ParamSelection;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.instrument.advice.ConstructorExit;
import com.hafnium.stackaugmentor.instrument.advice.ExitAdviceFactory;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.ClassLoaderConfigs;
import com.hafnium.stackaugmentor.runtime.config.LocalConfiguration;
import com.hafnium.stackaugmentor.runtime.config.Startup;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds the exit advice to the compiled classes of a project at build time, so no Java agent is needed.
 * Only the project's classes are changed; libraries are left alone.
 * At runtime, the application needs {@code stack-augmentor-runtime} on its classpath.
 *
 * <p>The arguments are paths of TOML configurations, in the order they apply, each optional (empty for none), all
 * before the built-in one ({@link AugmentorConfig#layered}). Their {@code [augment.annotations]},
 * {@code [augment.receiver]} and {@code [augment.params]} entries decide which receiver members and parameter values are
 * shown, and the classes and methods that need the advice for that are instrumented, as with the agent:
 * <ol start="0">
 *   <li>the build's own configuration, as {@code -Dstackaugmentor.config} names the JVM's;</li>
 *   <li>the context configuration, usually the application's {@code src/main/resources/stack-augmentor-context.toml};</li>
 *   <li>the module's local configuration, {@code src/main/resources/stack-augmentor.toml}: the instrumented classes get a
 *       {@link LocalConfiguration} naming it, so that they use it at runtime wherever it is on the classpath, also when
 *       classes and resources are in different build directories.</li>
 * </ol>
 * {@code -Dstackaugmentor.debug=true} on the build logs what gets instrumented. Without any (e.g. when discovered through {@code META-INF/net.bytebuddy/build.plugins}), the
 * built-in configuration applies alone ({@link AugmentorConfig#BUILT_IN_RESOURCE}): the methods that {@code @Augmented}
 * selects are instrumented.
 */
public final class ByteBuddyPlugin implements Plugin {

    private final ParamSelection parameters;
    private final TypeMatching matching;
    private final Advice advice;
    /** Names the local configuration on the instrumented classes; null without one. */
    private final AnnotationDescription localConfiguration;

    public ByteBuddyPlugin() {
        this(null, null, null, null, null, null);
    }

    /** @param buildFile the build's own TOML configuration, or empty for none */
    public ByteBuddyPlugin(String buildFile) {
        this(buildFile, "");
    }

    /**
     * @param buildFile   the build's own TOML configuration, or empty for none
     * @param contextFile a context TOML configuration, or empty for none
     */
    @Plugin.Factory.UsingReflection.Priority(1)
    public ByteBuddyPlugin(String buildFile, String contextFile) {
        this(buildFile, contextFile, "");
    }

    /**
     * @param buildFile   the build's own TOML configuration, or empty for none
     * @param contextFile a context TOML configuration, or empty for none
     * @param localFile   the module's local TOML configuration, or empty for none
     */
    @Plugin.Factory.UsingReflection.Priority(2)
    public ByteBuddyPlugin(String buildFile, String contextFile, String localFile) {
        // The ByteBuddy Gradle plugin reports a failing constructor without its cause, so Startup prints the reason too.
        this(buildFile.isBlank() ? null : Startup.load(Startup.BUILD_PLUGIN, () -> AugmentorConfig.load(Path.of(buildFile))),
                buildFile,
                contextFile.isBlank() ? null : Startup.load(Startup.BUILD_PLUGIN, () -> AugmentorConfig.load(Path.of(contextFile))),
                contextFile,
                localFile.isBlank() ? null : Startup.load(Startup.BUILD_PLUGIN, () -> local(Path.of(localFile))), localFile);
    }

    private record Local(AugmentorConfig config, String hash) {
    }

    private static Local local(Path file) {
        AugmentorConfig config = AugmentorConfig.load(file);
        try {
            return new Local(config, ClassLoaderConfigs.hash(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ByteBuddyPlugin(AugmentorConfig build, String buildLocation, AugmentorConfig file, String location, Local local,
                            String localLocation) {
        // Set on every build: the Gradle daemon keeps this class loaded from one build to the next.
        Log.setDebug(Log.debugRequested());
        List<AugmentorConfig> layers = new ArrayList<>();
        if (build != null) {
            layers.add(build);
        }
        if (file != null) {
            layers.add(file);
        }
        if (local != null) {
            layers.add(local.config());
        }
        layers.add(AugmentorConfig.builtIn());
        if (build != null) {
            Startup.describe(Startup.BUILD_PLUGIN, buildLocation, build);
        }
        if (file != null || build == null) {
            Startup.describe(Startup.BUILD_PLUGIN, file != null ? location : AugmentorConfig.BUILT_IN_LOCATION,
                    file != null ? file : AugmentorConfig.builtIn());
        }
        if (local != null) {
            Startup.describe(Startup.BUILD_PLUGIN, localLocation, local.config());
        }
        AugmentorConfig config = AugmentorConfig.layered(layers);
        this.localConfiguration = local == null ? null
                : AnnotationDescription.Builder.ofType(LocalConfiguration.class).define("value", local.hash()).build();
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
        DynamicType.Builder<?> instrumented = builder.visit(advice.on(methods)).visit(ConstructorExit.on(parameters, matching.constructors(typeDescription)));
        return localConfiguration != null ? instrumented.annotateType(localConfiguration) : instrumented;
    }

    @Override
    public void close() {
    }
}
