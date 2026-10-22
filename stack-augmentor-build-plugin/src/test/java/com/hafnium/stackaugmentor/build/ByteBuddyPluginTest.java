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
    void aConfigurationWithoutEntriesInstrumentsNothing(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        // The built-in configuration, which applies after the file, reads the annotations of all classes.
        Files.writeString(config, "[augment.annotations]\n\"**\" = false\n");
        ByteArrayOutputStream err = captureErr();

        assertFalse(new ByteBuddyPlugin(config.toString()).matches(tracked()));

        String output = err.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("WARN build plugin: the configuration has no [augment.receiver] or [augment.params] entries other "
                + "than \"-\" and no [augment.annotations] entry that is true, so nothing will be augmented"), output);
    }

    @Test
    void invalidTemplatesFailTheBuild(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "[augment]\nparamsFormat = \"($name: $value; ...)\"\n\n[augment.annotations]\n\"com.hafnium.**\" = true\n");
        ByteArrayOutputStream err = captureErr();
        ConfigException error = assertThrows(ConfigException.class, () -> new ByteBuddyPlugin(config.toString()));
        assertTrue(error.getMessage().startsWith("stack-augmentor.toml, line 2: paramsFormat must not contain '('"), error.getMessage());
        // Also printed, since the ByteBuddy Gradle plugin does not show the cause.
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("[stack-augmentor] ERROR build plugin: " + error.getMessage()), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anAnnotationsEntryEnablesTheAnnotations(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "[augment.annotations]\n\"com.hafnium.**\" = true\n");
        assertTrue(new ByteBuddyPlugin(config.toString()).matches(tracked()));
    }

    @Test
    void theAgentsIgnoredTypesAreLeftAlone(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "[augment.annotations]\n\"com.hafnium.**\" = true\n");
        assertFalse(new ByteBuddyPlugin(config.toString()).matches(TypeDescription.ForLoadedType.of(Annotated.class)));
    }

    @Test
    void debugDescribesTheConfigurationAsTheAgentDoes(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "debug = true\n[augment]\nmaxValueLength = 32\n[augment.annotations]\n\"com.hafnium.**\" = true\n");
        ByteArrayOutputStream err = captureErr();
        try {
            new ByteBuddyPlugin(config.toString());
        } finally {
            Log.setDebug(false);
        }
        String output = err.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("DEBUG build plugin: configuration " + config), output);
        assertTrue(output.contains("DEBUG build plugin: [augment.receiver] none"), output);
        assertTrue(output.contains("DEBUG build plugin: [augment.annotations] com.hafnium.**=true"), output);
        assertTrue(output.contains("DEBUG build plugin: [augment.params] none"), output);
        assertTrue(output.contains("DEBUG build plugin: [augment] frameFormat="), output);
        assertTrue(output.contains("maxValueLength=32"), output);
    }

    @Test
    void theAdviceAugmentsTheFramesOfTheClassesItIsAppliedTo(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "debug = true\n[augment.annotations]\n\"com.hafnium.**\" = true\n");
        ByteArrayOutputStream err = captureErr();
        TypeDescription type = TypeDescription.ForLoadedType.of(Failing.class);
        Class<?> instrumented;
        try (ByteBuddyPlugin plugin = new ByteBuddyPlugin(config.toString())) {
            assertTrue(plugin.matches(type));
            instrumented = plugin.apply(new ByteBuddy().redefine(Failing.class), type, ClassFileLocator.ForClassLoader.of(Failing.class.getClassLoader()))
                    .make()
                    .load(Failing.class.getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                    .getLoaded();
        } finally {
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
    void aLocalConfigurationAppliesAfterTheGlobalOneAndIsNamedOnTheInstrumentedClasses(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("stack-augmentor-global.toml");
        Files.writeString(config, "[augment.annotations]\n\"com.hafnium.buildfixture.Plain\" = false\n");
        Path local = dir.resolve("stack-augmentor.toml");
        String text = "[augment.annotations]\n\"com.hafnium.**\" = false\n";
        Files.writeString(local, text);
        TypeDescription type = TypeDescription.ForLoadedType.of(Failing.class);
        Class<?> instrumented;
        try (ByteBuddyPlugin plugin = new ByteBuddyPlugin(config.toString(), local.toString())) {
            // The local file's false entry decides where the global file has none.
            assertFalse(plugin.matches(type));
        }
        Files.writeString(local, "[augment]\nmaxValueLength = 10\n");
        try (ByteBuddyPlugin plugin = new ByteBuddyPlugin("", local.toString())) {
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
    void aLocalConfigurationMustNotSetTheSettingsOfTheWholeJvm(@TempDir Path dir) throws IOException {
        Path local = dir.resolve("stack-augmentor.toml");
        Files.writeString(local, "debug = true\n");
        captureErr();
        ConfigException error = assertThrows(ConfigException.class, () -> new ByteBuddyPlugin("", local.toString()));
        assertTrue(error.getMessage().startsWith("stack-augmentor.toml, line 1: 'debug' applies to the whole JVM"), error.getMessage());
        assertTrue(assertThrows(ConfigException.class, () -> new ByteBuddyPlugin("", dir.resolve("missing.toml").toString()))
                .getMessage().contains("not found"));
    }
}
