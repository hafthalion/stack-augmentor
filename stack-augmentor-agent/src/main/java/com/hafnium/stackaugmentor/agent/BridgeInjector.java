package com.hafnium.stackaugmentor.agent;

import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.Startup;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Extracts the embedded bridge jar and appends it to the bootstrap class loader search path.
 *
 * <p>The jar is kept in a directory of the temporary directory that belongs to the JVM's user and that only this user
 * can access, under a name that contains the jar's hash. Every JVM of the user with the same agent version uses the same
 * file: it is written only when it is missing or its content does not match the hash, and never deleted, because the
 * bootstrap class loader keeps it open until the JVM exits, and Windows cannot delete a file that is open.
 */
final class BridgeInjector {

    private static final String RESOURCE = "/META-INF/stack-augmentor/stack-augmentor-instrument-bridge.jar";
    private static final String PROBE = "com.hafnium.stackaugmentor.instrument.bridge.Dispatch";
    private static final String PREFIX = "stack-augmentor-instrument-bridge-";
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

    private BridgeInjector() {
    }

    static void inject(Instrumentation instrumentation) {
        if (bootstrapHasBridge()) {
            return;
        }
        try {
            byte[] jar = readResource();
            Path file;
            try {
                file = bridgeFile(privateDirectory(), jar);
            } catch (IOException e) {
                Log.warn(Startup.AGENT + ": cannot use the shared bridge jar, so this JVM uses its own copy: " + e.getMessage());
                file = ownCopy(jar);
            }
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(file.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A copy for this JVM only, when the user's directory cannot be used: {@code createTempFile} makes it readable by its
     * owner only. Where the file system allows it, it is deleted when the JVM exits.
     */
    private static Path ownCopy(byte[] jar) throws IOException {
        Path file = Files.createTempFile(PREFIX, ".jar");
        file.toFile().deleteOnExit();
        Files.write(file, jar);
        return file;
    }

    /**
     * The bridge jar in {@code directory}, named after its hash: the existing file if its content has that hash,
     * otherwise a new one, written next to it and then moved into place, so that a JVM starting at the same time never
     * sees a partly written jar.
     */
    static Path bridgeFile(Path directory, byte[] jar) throws IOException {
        byte[] hash = sha256(jar);
        Path file = directory.resolve(PREFIX + HexFormat.of().formatHex(hash, 0, 16) + ".jar");
        if (hasHash(file, hash)) {
            return file;
        }
        Path written = Files.createTempFile(directory, PREFIX, ".tmp");
        try {
            Files.write(written, jar);
            try {
                Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // On Windows, another JVM that uses the file keeps it from being replaced: that JVM wrote it with the
                // right content, unless it was damaged, which the hash tells.
                if (!hasHash(file, hash)) {
                    throw e;
                }
            }
        } finally {
            Files.deleteIfExists(written);
        }
        return file;
    }

    /**
     * The user's own directory for the bridge jar in the temporary directory, which on Unix is shared by all users. There
     * it is created accessible by its owner only, and an existing one is used only if it is a real directory that belongs
     * to this user and that no one else can access, so that no other user can place or replace the jar.
     */
    static Path privateDirectory() throws IOException {
        String user = System.getProperty("user.name", "user").replaceAll("[^A-Za-z0-9._-]", "_");
        Path directory = Path.of(System.getProperty("java.io.tmpdir")).resolve("stack-augmentor-" + user);
        boolean posix = Files.getFileAttributeView(directory.getParent(), PosixFileAttributeView.class) != null;
        try {
            FileAttribute<?>[] attributes = posix
                    ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)}
                    : new FileAttribute<?>[0];
            Files.createDirectory(directory, attributes);
        } catch (FileAlreadyExistsException e) {
            // created by an earlier JVM, or by someone else: checked below
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(directory + " is not a directory");
        }
        if (posix) {
            PosixFileAttributes attributes = Files.readAttributes(directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path probe = Files.createTempFile(directory, PREFIX, ".owner");
            try {
                if (!attributes.owner().equals(Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS))
                        || !OWNER_ONLY.containsAll(attributes.permissions())) {
                    throw new IOException(directory + " must belong to " + user + " and be accessible by its owner only");
                }
            } finally {
                Files.deleteIfExists(probe);
            }
        }
        // Elsewhere, i.e. on Windows, the temporary directory is the user's own already.
        return directory;
    }

    private static boolean hasHash(Path file, byte[] hash) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return Arrays.equals(sha256(Files.readAllBytes(file)), hash);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
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
