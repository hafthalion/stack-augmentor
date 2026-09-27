package com.hafnium.stackaugmentor.build;

import com.hafnium.stackaugmentor.StackTraceId;
import com.hafnium.stackaugmentor.runtime.ConfigException;
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

    private static TypeDescription annotated() {
        return TypeDescription.ForLoadedType.of(Annotated.class);
    }

    @Test
    void withoutConfigurationNothingIsInstrumented() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));

        assertFalse(new StackAugmentorByteBuddyPlugin().matches(annotated()));

        String output = err.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("WARN build plugin: the configuration has no [augment.receiver] or [augment.params] entries, "
                + "so nothing will be augmented"), output);
    }

    @Test
    void invalidTemplatesFailTheBuild(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "[augment]\nparamsFormat = \"($name: $id; ...)\"\n\n[augment.receiver]\n\"com.hafnium.**\" = \"@\"\n");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        ConfigException error = assertThrows(ConfigException.class, () -> new StackAugmentorByteBuddyPlugin(config.toString()));
        assertTrue(error.getMessage().startsWith("stack-augmentor.toml, line 2: paramsFormat must not contain '('"), error.getMessage());
        // Also printed, since the ByteBuddy Gradle plugin does not show the cause.
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("[stack-augmentor] ERROR " + error.getMessage()), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anAtEntryEnablesTheAnnotations(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("stack-augmentor.toml");
        Files.writeString(config, "[augment.receiver]\n\"com.hafnium.**\" = \"@\"\n");
        assertTrue(new StackAugmentorByteBuddyPlugin(config.toString()).matches(annotated()));
    }
}
