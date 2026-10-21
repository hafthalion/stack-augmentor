package com.hafnium.stackaugmentor.agent.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** The test JVM runs without the native library (-agentpath); stack-augmentor-it runs the live-stack mode with it. */
class LiveStackTest {

    @Test
    void withoutTheNativeLibraryTheModeIsNotRequestedAndCannotBeInstalled() {
        assertEquals(-1, NativeLibrary.loadedVersion());
        assertFalse(LiveStack.requested());
        // Nothing is changed: the instrumentation and the configurations are not used.
        assertEquals("the native library has version -1, the agent needs version " + NativeLibrary.VERSION, LiveStack.install(null, null));
    }
}
