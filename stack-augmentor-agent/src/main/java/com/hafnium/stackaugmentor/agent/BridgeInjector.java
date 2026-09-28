package com.hafnium.stackaugmentor.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.jar.JarFile;

/** Extracts the embedded bridge jar and appends it to the bootstrap class loader search path. */
final class BridgeInjector {

    private static final String PREFIX = "stack-augmentor-instrument-bridge-";
    private static final String RESOURCE = "/META-INF/stack-augmentor/stack-augmentor-instrument-bridge.jar";
    private static final String PROBE = "com.hafnium.stackaugmentor.instrument.bridge.Dispatch";

    private BridgeInjector() {
    }

    static void inject(Instrumentation instrumentation) {
        if (bootstrapHasBridge()) {
            return;
        }
        try {
            removeLeftovers();
            // A private copy for each JVM: a file shared through the temporary directory could be unreadable for
            // other users, or replaced by one of them. createTempFile makes it readable by its owner only.
            Path file = Files.createTempFile(PREFIX, ".jar");
            file.toFile().deleteOnExit();
            Files.write(file, readResource());
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(file.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Deletes the copies that earlier JVMs left behind. {@code deleteOnExit} cannot delete a jar that the bootstrap class
     * loader still holds open on Windows, so each run there leaves one. Only files older than this JVM are touched, and a
     * copy that a running JVM still uses cannot be deleted on Windows; on other systems, that JVM keeps its open file.
     * Anything that fails, e.g. another user's copy, is left alone.
     */
    private static void removeLeftovers() {
        Optional<Instant> started = ProcessHandle.current().info().startInstant();
        if (started.isEmpty()) {
            return;
        }
        Path directory = Path.of(System.getProperty("java.io.tmpdir"));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, PREFIX + "*.jar")) {
            for (Path file : files) {
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(started.get())) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException | RuntimeException e) {
                    // in use, or not ours: leave it
                }
            }
        } catch (IOException | RuntimeException e) {
            // no readable temporary directory: createTempFile reports it
        }
    }

    private static byte[] readResource() throws IOException {
        try (InputStream in = BridgeInjector.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the agent jar");
            }
            return in.readAllBytes();
        }
    }

    private static boolean bootstrapHasBridge() {
        try {
            Class.forName(PROBE, false, null);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
