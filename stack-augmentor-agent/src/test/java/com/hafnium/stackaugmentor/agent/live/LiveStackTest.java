package com.hafnium.stackaugmentor.agent.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hafnium.stackaugmentor.agent.Configured;
import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import com.hafnium.stackaugmentor.instrument.bridge.LiveDispatch;
import com.hafnium.stackaugmentor.instrument.live.LiveStackFrames;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.ClassLoaderConfigs;
import com.hafnium.stackaugmentor.runtime.config.Startup;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test JVM runs without the native library (-agentpath), with java.lang open (see build.gradle.kts). {@link FakeJvm}
 * stands in for it where a test needs the library or a failure; stack-augmentor-it/agent runs the live-stack mode itself.
 */
class LiveStackTest {

    /** A JVM with the native library, whose steps succeed unless a test changes them, and that records what was changed. */
    private static class FakeJvm implements LiveJvm {
        int version = NativeLibrary.VERSION;
        ReflectiveOperationException noLiveStackFrames;
        String layoutProblem;
        Map<String, Integer> options = Map.of();
        RuntimeException hookFailure;
        boolean hookCalled = true;

        boolean hooked;
        boolean removed;
        final List<ClassFileTransformer> transformers = new ArrayList<>();

        @Override
        public int nativeLibraryVersion() {
            return version;
        }

        @Override
        public LiveStackFrames liveStackFrames() throws ReflectiveOperationException {
            if (noLiveStackFrames != null) {
                throw noLiveStackFrames;
            }
            return new LiveStackFrames();
        }

        @Override
        public String checkLayout(LiveStackFrames live) {
            return layoutProblem;
        }

        @Override
        public int vmOption(String name, int defaultValue) {
            return options.getOrDefault(name, defaultValue);
        }

        @Override
        public Hook hookThrowable() {
            if (hookFailure != null) {
                throw hookFailure;
            }
            hooked = true;
            return () -> removed = true;
        }

        @Override
        public void fillInStackTrace(Throwable probe) {
            // What the code added to Throwable does.
            if (hookCalled) {
                LiveDispatch.onFill(probe);
            }
        }

        @Override
        public void addTransformer(ClassFileTransformer transformer) {
            transformers.add(transformer);
        }

        /** Whether the JVM is as it was. */
        boolean unchanged() {
            return (!hooked || removed) && transformers.isEmpty();
        }
    }

    private final AtomicInteger read = new AtomicInteger();

    private final ClassLoaderConfigs<Configured> contexts = ClassLoaderConfigs.create(Startup.AGENT, null, null, config -> {
        read.incrementAndGet();
        return Configured.of(config);
    });

    private final PrintStream originalErr = System.err;

    @AfterEach
    void restore() {
        LiveDispatch.install(null);
        Dispatch.install(null);
        Log.setDebug(false);
        System.setErr(originalErr);
    }

    private ByteArrayOutputStream captureErr() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        return err;
    }

    @Test
    void withoutTheNativeLibraryTheModeIsNotRequestedAndCannotBeInstalled() {
        assertEquals(-1, NativeLibrary.loadedVersion());
        assertFalse(LiveStack.requested());
        // Nothing is changed: the instrumentation and the configurations are not used.
        assertEquals("the native library has version -1, the agent needs version " + NativeLibrary.VERSION, LiveStack.install((Instrumentation) null, null));
    }

    @Test
    void aNativeLibraryWithoutTheCapabilityOrOfAnotherVersionIsNotUsed() {
        FakeJvm jvm = new FakeJvm();
        jvm.version = 0;
        assertEquals("the native library could not make the JVM keep local variables readable", LiveStack.install(jvm, contexts));
        jvm.version = NativeLibrary.VERSION + 1;
        assertEquals("the native library has version " + jvm.version + ", the agent needs version " + NativeLibrary.VERSION,
                LiveStack.install(jvm, contexts));
        assertTrue(jvm.unchanged());
        assertFalse(jvm.hooked);
    }

    @Test
    void aJdkWithoutUsableLiveStackFramesIsNotUsed() {
        FakeJvm jvm = new FakeJvm();
        jvm.noLiveStackFrames = new ClassNotFoundException("java.lang.LiveStackFrame");
        assertEquals("this JDK has no usable java.lang.LiveStackFrame: java.lang.ClassNotFoundException: java.lang.LiveStackFrame",
                LiveStack.install(jvm, contexts));
        assertFalse(jvm.hooked);

        jvm = new FakeJvm();
        jvm.layoutProblem = "the receiver is not the first local variable";
        assertEquals("the receiver is not the first local variable", LiveStack.install(jvm, contexts));
        assertFalse(jvm.hooked);
    }

    @Test
    void whenThrowableCannotBeChangedNothingIsChanged() {
        FakeJvm jvm = new FakeJvm();
        jvm.hookFailure = new IllegalStateException("retransformation failed");
        assertEquals("cannot add code to java.lang.Throwable: java.lang.IllegalStateException: retransformation failed", LiveStack.install(jvm, contexts));
        assertTrue(jvm.unchanged());
    }

    @Test
    void codeAddedToThrowableThatIsNotCalledIsRemovedAgain() {
        FakeJvm jvm = new FakeJvm();
        jvm.hookCalled = false;
        assertEquals("the code added to java.lang.Throwable is not called", LiveStack.install(jvm, contexts));
        assertTrue(jvm.hooked);
        assertTrue(jvm.unchanged());
    }

    @Test
    void theModeIsInstalledWhenTheCodeAddedToThrowableIsCalled() {
        FakeJvm jvm = new FakeJvm();
        Log.setDebug(true);
        ByteArrayOutputStream err = captureErr();
        assertNull(LiveStack.install(jvm, contexts));
        assertTrue(jvm.hooked);
        assertFalse(jvm.removed);
        assertInstanceOf(LiveStack.ClassLoaderRegistration.class, jvm.transformers.getFirst());
        String output = err.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("DEBUG agent: reading frames from the live stack (native library version " + NativeLibrary.VERSION
                + "), an object argument the JIT optimized away shows as '"), output);
    }

    @Test
    void withoutEliminatedAllocationsTheHintIsLeftOut() {
        FakeJvm jvm = new FakeJvm();
        jvm.options = Map.of("EliminateAllocations", 0);
        Log.setDebug(true);
        ByteArrayOutputStream err = captureErr();
        assertNull(LiveStack.install(jvm, contexts));
        String output = err.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("DEBUG agent: reading frames from the live stack (native library version " + NativeLibrary.VERSION
                + ")" + System.lineSeparator()), output);
    }

    @Test
    void aClassLoadersConfigurationIsReadOnceWhenItDefinesItsFirstClass(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("stack-augmentor-context.toml"), "[augment.exceptions]\n\"**\" = true\n");
        LiveStack.ClassLoaderRegistration registration = new LiveStack.ClassLoaderRegistration(contexts);
        // The base configuration.
        assertEquals(1, read.get());
        try (URLClassLoader application = new URLClassLoader(new URL[] {dir.toUri().toURL()}, LiveStackTest.class.getClassLoader())) {
            // Classes are left unchanged.
            assertNull(registration.transform(application, "com/acme/Order", null, null, new byte[0]));
            assertEquals(2, read.get());
            registration.transform(application, "com/acme/Customer", null, null, new byte[0]);
            assertEquals(2, read.get());
        }
        // The bootstrap class loader's classes use the base configuration.
        assertNull(registration.transform(null, "java/lang/Object", null, null, new byte[0]));
        assertEquals(2, read.get());
    }

    @Test
    void vmOptionsAreReadFromHotSpot() {
        RunningJvm jvm = new RunningJvm(null);
        assertEquals(42, jvm.vmOption("NoSuchOption", 42));
        assertTrue(jvm.vmOption("MaxJavaStackTraceDepth", -1) >= 0);
        int eliminateAllocations = jvm.vmOption("EliminateAllocations", -1);
        assertTrue(eliminateAllocations == 0 || eliminateAllocations == 1, String.valueOf(eliminateAllocations));
        assertEquals(-1, jvm.nativeLibraryVersion());
    }
}
