package com.hafnium.stackaugmentor.agent.live;

/**
 * The native library of the live-stack mode ({@code stack-augmentor-native}), loaded with {@code -agentpath}. The JVM
 * also looks up native methods in the libraries of {@code -agentpath}, so {@link #version()} links to it if it was
 * loaded.
 */
final class NativeLibrary {

    /** What the agent expects of the library; raised together with the library's {@code VERSION}. */
    static final int VERSION = 1;

    private NativeLibrary() {
    }

    /**
     * The library's version once the JVM keeps local variables readable, or 0 if it could not.
     *
     * @throws UnsatisfiedLinkError if the library was not loaded
     */
    static native int version();

    /** The loaded library's version, 0 if it could not get what it needs, or -1 if it was not loaded. */
    static int loadedVersion() {
        try {
            return version();
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }
}
