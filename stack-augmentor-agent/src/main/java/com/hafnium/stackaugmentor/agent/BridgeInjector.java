package com.hafnium.stackaugmentor.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
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
            // A private copy for each JVM: a file shared through the temporary directory could be unreadable for
            // other users, or replaced by one of them. createTempFile makes it readable by its owner only.
            Path file = Files.createTempFile("stack-augmentor-instrument-bridge-", ".jar");
            file.toFile().deleteOnExit();
            Files.write(file, readResource());
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(file.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
