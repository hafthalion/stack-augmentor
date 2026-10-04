package com.hafnium.stackaugmentor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RootCauseFirstTest {

    @Test
    void printsTheRootCauseFirstAndEachWrapperWithTheFramesItAdds() {
        Exception root = exception(new IllegalArgumentException("root"), "c", "b", "a", "main");
        Exception middle = exception(new IllegalStateException("middle", root), "b", "a", "main");
        Exception outer = exception(new RuntimeException("outer", middle), "a", "main");

        assertEquals("""
                java.lang.IllegalArgumentException: root
                \tat T.c(T.java:1)
                \t... 3 common frames omitted
                Wrapped by: java.lang.IllegalStateException: middle
                \tat T.b(T.java:1)
                \t... 2 common frames omitted
                Wrapped by: java.lang.RuntimeException: outer
                \tat T.a(T.java:1)
                \tat T.main(T.java:1)
                """, RootCauseFirst.format(outer));
    }

    @Test
    void printsSuppressedExceptionsIndentedAfterTheFramesOfTheirException() {
        Exception closing = exception(new IllegalStateException("closing"), "close", "a", "main");
        Exception outer = exception(new RuntimeException("outer"), "a", "main");
        outer.addSuppressed(closing);

        assertEquals("""
                java.lang.RuntimeException: outer
                \tat T.a(T.java:1)
                \tat T.main(T.java:1)
                \tSuppressed: java.lang.IllegalStateException: closing
                \t\tat T.close(T.java:1)
                \t\t... 2 common frames omitted
                """, RootCauseFirst.format(outer));
    }

    @Test
    void stopsAtACircularReference() {
        Exception first = exception(new RuntimeException("first"), "a");
        Exception second = exception(new RuntimeException("second", first), "a");
        first.initCause(second);

        assertEquals("""
                [CIRCULAR REFERENCE: java.lang.RuntimeException: second]
                Wrapped by: java.lang.RuntimeException: first
                \t... 1 common frames omitted
                Wrapped by: java.lang.RuntimeException: second
                \tat T.a(T.java:1)
                """, RootCauseFirst.format(second));
    }

    private static <T extends Throwable> T exception(T throwable, String... methods) {
        StackTraceElement[] trace = new StackTraceElement[methods.length];
        for (int i = 0; i < methods.length; i++) {
            trace[i] = new StackTraceElement("T", methods[i], "T.java", 1);
        }
        throwable.setStackTrace(trace);
        return throwable;
    }
}
