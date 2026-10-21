package com.hafnium.stackaugmentor.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bridge jar's file in the user's directory; the bridge classes are not on the test's bootstrap class path. */
class BridgeInjectorTest {

    private static final byte[] JAR = "jar content".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tmp;

    private String originalTmpdir;

    @BeforeEach
    void useTemporaryDirectory() {
        originalTmpdir = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", tmp.toString());
    }

    @AfterEach
    void restoreTemporaryDirectory() {
        System.setProperty("java.io.tmpdir", originalTmpdir);
    }

    private static List<Path> files(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.toList();
        }
    }

    private static String user() {
        return System.getProperty("user.name", "user").replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static boolean posix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }

    @Test
    void theJarIsWrittenOnceUnderTheNameOfItsHash() throws IOException {
        Path file = BridgeInjector.bridgeFile(tmp, JAR);
        assertTrue(file.getFileName().toString().matches("stack-augmentor-instrument-bridge-[0-9a-f]{32}\\.jar"), file.toString());
        assertArrayEquals(JAR, Files.readAllBytes(file));
        // No temporary file is left behind.
        assertEquals(List.of(file), files(tmp));

        // The next JVM uses the file as it is.
        FileTime written = FileTime.fromMillis(1_000_000);
        Files.setLastModifiedTime(file, written);
        assertEquals(file, BridgeInjector.bridgeFile(tmp, JAR));
        assertEquals(written, Files.getLastModifiedTime(file));

        // Another version of the agent has its own file.
        assertNotEquals(file, BridgeInjector.bridgeFile(tmp, "other".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aDamagedJarIsReplaced() throws IOException {
        Path file = BridgeInjector.bridgeFile(tmp, JAR);
        Files.writeString(file, "damaged");
        assertEquals(file, BridgeInjector.bridgeFile(tmp, JAR));
        assertArrayEquals(JAR, Files.readAllBytes(file));
        assertEquals(List.of(file), files(tmp));
    }

    @Test
    void theUsersDirectoryIsCreatedForTheOwnerOnly() throws IOException {
        Path directory = BridgeInjector.privateDirectory();
        assertEquals(tmp.resolve("stack-augmentor-" + user()), directory);
        assertTrue(Files.isDirectory(directory));
        if (posix(directory)) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
        }
        // An earlier JVM's directory is used again; the ownership probe is removed.
        assertEquals(directory, BridgeInjector.privateDirectory());
        assertEquals(List.of(), files(directory));
    }

    @Test
    void aFileInPlaceOfTheDirectoryIsRejected() throws IOException {
        Files.writeString(tmp.resolve("stack-augmentor-" + user()), "not a directory");
        IOException e = assertThrows(IOException.class, BridgeInjector::privateDirectory);
        assertTrue(e.getMessage().endsWith(" is not a directory"), e.getMessage());
    }

    @Test
    void aDirectoryThatOthersCanAccessIsRejected() throws IOException {
        assumeTrue(posix(tmp));
        Files.createDirectory(tmp.resolve("stack-augmentor-" + user()), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        IOException e = assertThrows(IOException.class, BridgeInjector::privateDirectory);
        assertTrue(e.getMessage().endsWith(" must belong to " + user() + " and be accessible by its owner only"), e.getMessage());
    }

    /** An Instrumentation that records the jars appended to the bootstrap class loader search and does nothing else. */
    private static Instrumentation recording(List<JarFile> appended) {
        return (Instrumentation) Proxy.newProxyInstance(BridgeInjectorTest.class.getClassLoader(), new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("appendToBootstrapClassLoaderSearch")) {
                        appended.add((JarFile) args[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void theEmbeddedBridgeJarIsAppendedToTheBootstrapClassLoader() throws IOException {
        List<JarFile> appended = new ArrayList<>();
        BridgeInjector.inject(recording(appended));
        try (JarFile jar = appended.getFirst()) {
            assertEquals(1, appended.size());
            assertEquals(tmp.resolve("stack-augmentor-" + user()), Path.of(jar.getName()).getParent());
            assertNotNull(jar.getEntry("com/hafnium/stackaugmentor/instrument/bridge/Dispatch.class"));
        }
    }

    @Test
    void withoutTheUsersDirectoryTheJvmUsesItsOwnCopy() throws IOException {
        Files.writeString(tmp.resolve("stack-augmentor-" + user()), "not a directory");
        List<JarFile> appended = new ArrayList<>();
        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            BridgeInjector.inject(recording(appended));
        } finally {
            System.setErr(originalErr);
        }
        try (JarFile jar = appended.getFirst()) {
            assertNotNull(jar.getEntry("com/hafnium/stackaugmentor/instrument/bridge/Dispatch.class"));
            assertTrue(Path.of(jar.getName()).getFileName().toString().startsWith("stack-augmentor-instrument-bridge-"), jar.getName());
            assertNotEquals(tmp, Path.of(jar.getName()).getParent());
        }
        String output = err.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[stack-augmentor] WARN agent: cannot use the shared bridge jar, so this JVM uses its own copy: "), output);
        assertTrue(output.contains(" is not a directory"), output);
    }
}
