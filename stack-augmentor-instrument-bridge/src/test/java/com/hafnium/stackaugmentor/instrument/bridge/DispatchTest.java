package com.hafnium.stackaugmentor.instrument.bridge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The test resources register {@link UnusableHandler} as the service: only {@link #anUnusableServiceIsReportedOnce()}
 * calls Dispatch without an installed handler, so that it is the one that looks the service up.
 */
class DispatchTest {

    /** Registered as the service in the test resources; cannot be created. */
    public static final class UnusableHandler implements Dispatch.Handler {
        public UnusableHandler() {
            throw new IllegalStateException("no handler here");
        }

        @Override
        public void onThrow(Object self, Throwable thrown, String receiverKey, Class<?> ownerClass, String owner, String method, Object[] args,
                            String[] paramNames) {
        }
    }

    private record Call(Object self, Throwable thrown, String receiverKey, Class<?> ownerClass, String owner, String method) {
    }

    @AfterEach
    void uninstall() {
        Dispatch.install(null);
        LiveDispatch.install(null);
    }

    @Test
    void theHandlerGetsTheCallWithTheDeclaringClass() {
        List<Call> calls = new ArrayList<>();
        Dispatch.install((self, thrown, receiverKey, ownerClass, owner, method, args, paramNames) ->
                calls.add(new Call(self, thrown, receiverKey, ownerClass, owner, method)));
        Throwable thrown = new IllegalStateException();
        Dispatch.onThrow(this, thrown, "key", DispatchTest.class, "run", null, null);
        assertEquals(List.of(new Call(this, thrown, "key", DispatchTest.class, DispatchTest.class.getName(), "run")), calls);
    }

    @Test
    void classesInstrumentedByAnEarlierVersionPassTheClassNameOnly() {
        List<Call> calls = new ArrayList<>();
        Dispatch.install((self, thrown, receiverKey, ownerClass, owner, method, args, paramNames) ->
                calls.add(new Call(self, thrown, receiverKey, ownerClass, owner, method)));
        Throwable thrown = new IllegalStateException();
        Dispatch.onThrow(null, thrown, null, "com.acme.Order", "ship", null, null);
        assertEquals(List.of(new Call(null, thrown, null, null, "com.acme.Order", "ship")), calls);
    }

    @Test
    void anExceptionWhileTheHandlerRunsIsNotHandledAgainAndNeverReachesTheApplication() {
        List<Throwable> handled = new ArrayList<>();
        Dispatch.install((self, thrown, receiverKey, ownerClass, owner, method, args, paramNames) -> {
            handled.add(thrown);
            // E.g. a receiver member's getter that throws from an instrumented method.
            Dispatch.onThrow(null, new IllegalArgumentException("nested"), null, DispatchTest.class, "getId", null, null);
            throw new IllegalStateException("handler failed");
        });
        Throwable thrown = new RuntimeException("application");
        assertDoesNotThrow(() -> Dispatch.onThrow(null, thrown, null, DispatchTest.class, "run", null, null));
        assertEquals(List.of(thrown), handled);
    }

    @Test
    void anUnusableServiceIsReportedOnce() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            Dispatch.onThrow(null, new IllegalStateException(), null, DispatchTest.class, "run", null, null);
            Dispatch.onThrow(null, new IllegalStateException(), null, DispatchTest.class, "run", null, null);
        } finally {
            System.setErr(originalErr);
        }
        assertEquals(
                "[stack-augmentor] WARN runtime: cannot create the stack trace handler, so stack traces stay unchanged: "
                        + "java.lang.IllegalStateException: no handler here" + System.lineSeparator(),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aFailingLiveStackHandlerNeverReachesTheApplication() {
        List<String> calls = new ArrayList<>();
        LiveDispatch.install(new LiveDispatch.Handler() {
            @Override
            public void onFill(Throwable thrown) {
                calls.add("fill");
                throw new IllegalStateException("fill");
            }

            @Override
            public void onTrace(Throwable thrown, StackTraceElement[] trace) {
                calls.add("trace");
                throw new IllegalStateException("trace");
            }

            @Override
            public void onSetTrace(Throwable thrown) {
                calls.add("set");
                throw new IllegalStateException("set");
            }
        });
        Throwable thrown = new RuntimeException();
        assertDoesNotThrow(() -> {
            LiveDispatch.onFill(thrown);
            LiveDispatch.onTrace(thrown, new StackTraceElement[0]);
            LiveDispatch.onSetTrace(thrown);
        });
        assertEquals(List.of("fill", "trace", "set"), calls);
    }
}
