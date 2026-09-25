package com.hafnium.stackaugmentor.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarFile;

/** Extracts the embedded bridge jar and appends it to the bootstrap class loader search path. */
final class BridgeInjector {

    private static final String RESOURCE = "/META-INF/stack-augmentor/stack-augmentor-instrument-bridge.jar";
    private static final String PROBE = "com.hafnium.stackaugmentor.instrument.bridge.Dispatch";

    private BridgeInjector() {
    }

    static void inject(Instrumentation instrumentation) {
        if (bootstrapHasBridge()) {
            return;
        }
        try {
            byte[] bytes = readResource();
            // Named after the content, so restarts reuse the file instead of leaving a new one each time.
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
            Path file = Path.of(System.getProperty("java.io.tmpdir"), "stack-augmentor-instrument-bridge-" + hash + ".jar");
            if (!Files.isRegularFile(file) || Files.size(file) != bytes.length) {
                Path temp = Files.createTempFile(file.getParent(), "stack-augmentor-instrument-bridge", ".tmp");
                Files.write(temp, bytes);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.deleteIfExists(temp); // another JVM wrote it concurrently, or it is in use
                }
            }
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(file.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
