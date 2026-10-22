package com.hafnium.stackaugmentor.build;

import com.hafnium.stackaugmentor.instrument.ParamSelection;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.instrument.advice.ConstructorExit;
import com.hafnium.stackaugmentor.instrument.advice.ExitAdviceFactory;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.ClassLoaderConfigs;
import com.hafnium.stackaugmentor.runtime.config.ConfigException;
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
import java.nio.charset.StandardCharsets;
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
 *   <li>the build's own configuration, as the agent's file is of the JVM: the only one that may have
 *       {@code [settings]}, whose {@code debug} logs what gets instrumented;</li>
 *   <li>the global configuration, usually the application's {@code src/main/resources/stack-augmentor-context.toml};</li>
 *   <li>the module's local configuration, {@code src/main/resources/stack-augmentor.toml}: the instrumented classes get a
 *       {@link LocalConfiguration} naming it, so that they use it at runtime wherever it is on the classpath, also when
 *       classes and resources are in different build directories.</li>
 * </ol>
 * The global and the local configuration are on the application's classpath too, so like every file there they must not
 * have {@code [settings]}. Without any (e.g. when discovered through {@code META-INF/net.bytebuddy/build.plugins}), the
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

    /** @param settingsFile the build's own TOML configuration, which may have {@code [settings]}, or empty for none */
    public ByteBuddyPlugin(String settingsFile) {
        this(settingsFile, "");
    }

    /**
     * @param settingsFile the build's own TOML configuration, which may have {@code [settings]}, or empty for none
     * @param configFile   a global TOML configuration, or empty for none
     */
    @Plugin.Factory.UsingReflection.Priority(1)
    public ByteBuddyPlugin(String settingsFile, String configFile) {
        this(settingsFile, configFile, "");
    }

    /**
     * @param settingsFile the build's own TOML configuration, which may have {@code [settings]}, or empty for none
     * @param configFile   a global TOML configuration, or empty for none
     * @param localFile    the module's local TOML configuration, or empty for none
     */
    @Plugin.Factory.UsingReflection.Priority(2)
    public ByteBuddyPlugin(String settingsFile, String configFile, String localFile) {
        // The ByteBuddy Gradle plugin reports a failing constructor without its cause, so Startup prints the reason too.
        this(settingsFile.isBlank() ? null : Startup.load(Startup.BUILD_PLUGIN, () -> AugmentorConfig.load(Path.of(settingsFile))),
                settingsFile,
                configFile.isBlank() ? null : Startup.load(Startup.BUILD_PLUGIN, () -> AugmentorConfig.load(Path.of(configFile), false)),
                configFile,
                localFile.isBlank() ? null : Startup.load(Startup.BUILD_PLUGIN, () -> local(Path.of(localFile))), localFile);
    }

    private record Local(AugmentorConfig config, String hash) {
    }

    private static Local local(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("Configuration file not found: " + file);
        }
        try {
            byte[] content = Files.readAllBytes(file);
            return new Local(AugmentorConfig.parse(new String(content, StandardCharsets.UTF_8), file.getFileName().toString(), false),
                    ClassLoaderConfigs.hash(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ByteBuddyPlugin(AugmentorConfig settings, String settingsLocation, AugmentorConfig file, String location, Local local,
                            String localLocation) {
        List<AugmentorConfig> layers = new ArrayList<>();
        if (settings != null) {
            layers.add(settings);
        }
        if (file != null) {
            layers.add(file);
        }
        if (local != null) {
            layers.add(local.config());
        }
        layers.add(AugmentorConfig.builtIn());
        Log.setDebug(settings != null && settings.debug());
        if (settings != null) {
            Startup.describe(Startup.BUILD_PLUGIN, settingsLocation, settings);
        }
        if (file != null || settings == null) {
            Startup.describe(Startup.BUILD_PLUGIN, file != null ? location : AugmentorConfig.BUILT_IN_LOCATION,
                    file != null ? file : AugmentorConfig.builtIn());
        }
        if (local != null) {
            Startup.describe(Startup.BUILD_PLUGIN, localLocation, local.config());
        }
        AugmentorConfig config = AugmentorConfig.layered(layers);
        this.localConfiguration = local == null ? null
                : AnnotationDescription.Builder.ofType(LocalConfiguration.class).define("value", local.hash()).build();
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
        DynamicType.Builder<?> instrumented = builder.visit(advice.on(methods)).visit(ConstructorExit.on(parameters, matching.constructors(typeDescription)));
        return localConfiguration != null ? instrumented.annotateType(localConfiguration) : instrumented;
    }

    @Override
    public void close() {
    }
}
