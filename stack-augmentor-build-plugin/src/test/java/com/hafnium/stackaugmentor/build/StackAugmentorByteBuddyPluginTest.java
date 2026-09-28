package com.hafnium.stackaugmentor.build;

import com.hafnium.buildfixture.Tracked;
import com.hafnium.stackaugmentor.StackTraceId;
import com.hafnium.stackaugmentor.runtime.ConfigException;
import com.hafnium.stackaugmentor.runtime.Log;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackAugmentorByteBuddyPluginTest {

    /** In a stack-augmentor package, which neither the agent nor the build plugin instruments. */
    static class Annotated {
        @StackTraceId
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
    void withoutConfigurationNothingIsInstrumented() {
        ByteArrayOutputStream err = captureErr();

        assertFalse(new StackAugmentorByteBuddyPlugin().matches(tracked()));

        String output = err.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("WARN build plugin: the configuration has no [augment.receiver] or [augment.params] entries, "
                + "so nothing will be augmented"), output);
    }

    @Test
    void invalidTemplatesFailTheBuild(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "[augment]\nparamsFormat = \"($name: $id; ...)\"\n\n[augment.receiver]\n\"com.hafnium.**\" = \"@\"\n");
        ByteArrayOutputStream err = captureErr();
        ConfigException error = assertThrows(ConfigException.class, () -> new StackAugmentorByteBuddyPlugin(config.toString()));
        assertTrue(error.getMessage().startsWith("stack-augmentor.toml, line 2: paramsFormat must not contain '('"), error.getMessage());
        // Also printed, since the ByteBuddy Gradle plugin does not show the cause.
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("[stack-augmentor] ERROR build plugin: " + error.getMessage()), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anAtEntryEnablesTheAnnotations(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "[augment.receiver]\n\"com.hafnium.**\" = \"@\"\n");
        assertTrue(new StackAugmentorByteBuddyPlugin(config.toString()).matches(tracked()));
    }

    @Test
    void theAgentsIgnoredTypesAreLeftAlone(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "[augment.receiver]\n\"com.hafnium.**\" = \"@\"\n");
        assertFalse(new StackAugmentorByteBuddyPlugin(config.toString()).matches(TypeDescription.ForLoadedType.of(Annotated.class)));
    }

    @Test
    void debugDescribesTheConfigurationAsTheAgentDoes(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "debug = true\n[augment]\nmaxParams = 3\n[augment.receiver]\n\"com.hafnium.**\" = \"@\"\n");
        ByteArrayOutputStream err = captureErr();
        try {
            new StackAugmentorByteBuddyPlugin(config.toString());
        } finally {
            Log.setDebug(false);
        }
        String output = err.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("DEBUG build plugin: configuration " + config), output);
        assertTrue(output.contains("DEBUG build plugin: [augment.receiver] com.hafnium.**=@"), output);
        assertTrue(output.contains("DEBUG build plugin: [augment.params] none"), output);
        assertTrue(output.contains("DEBUG build plugin: [augment] frameFormat="), output);
        assertTrue(output.contains("maxParams=3"), output);
    }
}
