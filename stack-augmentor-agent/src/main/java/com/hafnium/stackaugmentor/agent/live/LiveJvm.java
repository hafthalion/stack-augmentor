package com.hafnium.stackaugmentor.agent.live;

import com.hafnium.stackaugmentor.instrument.live.LiveStackFrames;

import java.lang.instrument.ClassFileTransformer;

/** What {@link LiveStack} needs from the JVM it runs in: {@link RunningJvm}, or in tests, one that fails where they need it. */
interface LiveJvm {

    /** The loaded native library's version, 0 if it could not get what it needs, or -1 if it was not loaded. */
    int nativeLibraryVersion();

    /** @throws ReflectiveOperationException if this JDK has no live stack frames, or {@code java.lang} is not open */
    LiveStackFrames liveStackFrames() throws ReflectiveOperationException;

    /** @return null, or what is not as expected of the live stack frames */
    String checkLayout(LiveStackFrames live);

    /** A numeric or boolean ({@code 1} or {@code 0}) HotSpot option, or the default if the VM does not tell. */
    int vmOption(String name, int defaultValue);

    /**
     * Adds the code of the live-stack mode to {@link Throwable}.
     *
     * @throws RuntimeException if it cannot
     */
    Hook hookThrowable();

    /** Records the stack trace of {@code probe}, as for every throwable: with the hook, that calls the handler. */
    void fillInStackTrace(Throwable probe);

    void addTransformer(ClassFileTransformer transformer);

    /** The code added to {@link Throwable}. */
    interface Hook {
        /** Removes it again. */
        void remove();
    }
}
