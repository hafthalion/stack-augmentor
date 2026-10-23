package com.hafnium.stackaugmentor.build;

import com.hafnium.buildfixture.Failing;
import com.hafnium.buildfixture.Plain;
import com.hafnium.buildfixture.Tracked;
import com.hafnium.stackaugmentor.AugmentedReceiver;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.ClassLoaderConfigs;
import com.hafnium.stackaugmentor.runtime.config.ConfigException;
import com.hafnium.stackaugmentor.runtime.config.LocalConfiguration;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteBuddyPluginTest {

    /** In a stack-augmentor package, which neither the agent nor the build plugin instruments. */
    static class Annotated {
        @AugmentedReceiver
        String id = "a-1";

        void fail() {
        }
    }

    private final PrintStream originalErr = System.err;

    @AfterEach
    void restore() {
        System.setErr(originalErr);
    }

    private static TypeDescription tracked() {
        return TypeDescription.ForLoadedType.of(Tracked.class);
    }

    private ByteArrayOutputStream captureErr() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        return err;
    }

    @Test
    void withoutConfigurationTheAnnotatedClassesAreInstrumented() {
        ByteArrayOutputStream err = captureErr();

        ByteBuddyPlugin plugin = new ByteBuddyPlugin();
        assertTrue(plugin.matches(tracked()));
        assertFalse(plugin.matches(TypeDescription.ForLoadedType.of(Plain.class)));

        String output = err.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("WARN"), output);
    }

    @Test
    void invalidTemplatesFailTheBuild(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "[augment]\nparamsFormat = \"($name: $value; ...)\"\n");
        ByteArrayOutputStream err = captureErr();
        ConfigException error = assertThrows(ConfigException.class, () -> new ByteBuddyPlugin("", config.toString()));
        assertTrue(error.getMessage().startsWith("stack-augmentor.toml, line 2: paramsFormat must not contain '('"), error.getMessage());
        // Also printed, since the ByteBuddy Gradle plugin does not show the cause.
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("[stack-augmentor] ERROR build plugin: " + error.getMessage()), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theAgentsIgnoredTypesAreLeftAlone() {
        assertFalse(new ByteBuddyPlugin().matches(TypeDescription.ForLoadedType.of(Annotated.class)));
    }

    @Test
    void debugDescribesTheConfigurationAsTheAgentDoes(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "[augment]\nmaxValueLength = 32\n");
        ByteArrayOutputStream err = captureErr();
        withDebug(() -> new ByteBuddyPlugin(config.toString()));
        String output = err.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("DEBUG build plugin: configuration " + config), output);
        assertTrue(output.contains("DEBUG build plugin: [augment.receiver] none"), output);
        assertTrue(output.contains("DEBUG build plugin: [augment.params] none"), output);
        assertTrue(output.contains("DEBUG build plugin: [augment] frameFormat="), output);
        assertTrue(output.contains("maxValueLength=32"), output);
    }

    @Test
    void theAdviceAugmentsTheFramesOfTheClassesItIsAppliedTo(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "[augment]\n");
        ByteArrayOutputStream err = captureErr();
        TypeDescription type = TypeDescription.ForLoadedType.of(Failing.class);
        Class<?> instrumented;
        System.setProperty(Log.DEBUG_PROPERTY, "true");
        try (ByteBuddyPlugin plugin = new ByteBuddyPlugin(config.toString())) {
            assertTrue(plugin.matches(type));
            instrumented = plugin.apply(new ByteBuddy().redefine(Failing.class), type, ClassFileLocator.ForClassLoader.of(Failing.class.getClassLoader()))
                    .make()
                    .load(Failing.class.getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                    .getLoaded();
        } finally {
            System.clearProperty(Log.DEBUG_PROPERTY);
            Log.setDebug(false);
        }
        String output = err.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("DEBUG instrumenting com.hafnium.buildfixture.Failing (receiver members from @AugmentedReceiver): fail"), output);

        // At runtime, the advice calls the handler of stack-augmentor-runtime, here with its built-in configuration.
        Object failing = instrumented.getConstructor().newInstance();
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> instrumented.getMethod("fail").invoke(failing));
        assertEquals("com.hafnium.buildfixture.Failing{id=f-1}", thrown.getCause().getStackTrace()[0].getClassName());
        assertEquals("fail", thrown.getCause().getStackTrace()[0].getMethodName());
    }

    @Test
    void aLocalConfigurationAppliesAfterTheContextOneAndIsNamedOnTheInstrumentedClasses(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("stack-augmentor-context.toml");
        Files.writeString(config, "[augment.receiver]\n\"com.hafnium.buildfixture.Plain::*\" = \"-\"\n");
        Path local = dir.resolve("stack-augmentor.toml");
        String text = "[augment.receiver]\n\"com.hafnium.**::*\" = \"-\"\n";
        Files.writeString(local, text);
        TypeDescription type = TypeDescription.ForLoadedType.of(Failing.class);
        Class<?> instrumented;
        try (ByteBuddyPlugin plugin = new ByteBuddyPlugin("", config.toString(), local.toString())) {
            // The local file's "-" entry decides where the context file has none.
            assertFalse(plugin.matches(type));
        }
        Files.writeString(local, "[augment]\nmaxValueLength = 10\n");
        try (ByteBuddyPlugin plugin = new ByteBuddyPlugin("", "", local.toString())) {
            assertTrue(plugin.matches(type));
            instrumented = plugin.apply(new ByteBuddy().redefine(Failing.class), type, ClassFileLocator.ForClassLoader.of(Failing.class.getClassLoader()))
                    .make()
                    .load(Failing.class.getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                    .getLoaded();
        }
        LocalConfiguration named = instrumented.getAnnotation(LocalConfiguration.class);
        assertEquals(ClassLoaderConfigs.hash(Files.readAllBytes(local)), named.value());
    }

    @Test
    void theBuildFileAppliesBeforeTheContextOne(@TempDir Path dir) throws IOException {
        Path build = dir.resolve("build.toml");
        Files.writeString(build, "[augment.receiver]\n\"com.hafnium.buildfixture.Failing::*\" = \"-\"\n");
        Path context = dir.resolve("stack-augmentor-context.toml");
        Files.writeString(context, "[augment.receiver]\n\"com.hafnium.buildfixture.Failing::*\" = \"id\"\n");
        TypeDescription type = TypeDescription.ForLoadedType.of(Failing.class);
        try (ByteBuddyPlugin plugin = new ByteBuddyPlugin(build.toString(), context.toString())) {
            assertFalse(plugin.matches(type));
        }
        try (ByteBuddyPlugin plugin = new ByteBuddyPlugin("", context.toString())) {
            assertTrue(plugin.matches(type));
        }
    }

    @Test
    void aMissingFileFailsTheBuild(@TempDir Path dir) {
        captureErr();
        assertTrue(assertThrows(ConfigException.class, () -> new ByteBuddyPlugin("", "", dir.resolve("missing.toml").toString()))
                .getMessage().contains("not found"));
    }

    @Test
    void debugIsTurnedOnByTheSystemPropertyOnEveryBuild(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("build.toml");
        Files.writeString(config, "[augment]\nmaxValueLength = 32\n");
        captureErr();
        withDebug(() -> new ByteBuddyPlugin(config.toString()));
        // The Gradle daemon keeps the plugin's classes loaded: the next build without the property has no debug output.
        new ByteBuddyPlugin(config.toString());
        assertFalse(Log.isDebug());
    }

    private static void withDebug(Runnable action) {
        System.setProperty(Log.DEBUG_PROPERTY, "true");
        try {
            action.run();
            assertTrue(Log.isDebug());
        } finally {
            System.clearProperty(Log.DEBUG_PROPERTY);
            Log.setDebug(false);
        }
    }
}
