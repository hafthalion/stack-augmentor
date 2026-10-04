package com.hafnium.stackaugmentor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Formats a throwable with its causes in reverse order: the root cause first, then each exception that wrapped it as
 * {@code Wrapped by:}, e.g.
 *
 * <pre>
 * java.io.IOException: disk full
 * 	at com.example.Store.write(Store.java:40)
 * 	... 3 common frames omitted
 * Wrapped by: java.lang.IllegalStateException: order 42 not saved
 * 	at com.example.Orders.save(Orders.java:12)
 * 	at com.example.Main.main(Main.java:5)
 * </pre>
 *
 * <p>Read from the top, the frames then follow the stack upwards, each printed once: every exception leaves out the
 * frames it shares with the one that wrapped it, which follows it. Suppressed exceptions follow the frames of the
 * exception they were suppressed in, indented and headed {@code Suppressed:}, their own causes again root first. It
 * prints the stack traces as {@link Throwable#getStackTrace()} returns them, so with the ids that Stack Augmentor
 * added.
 *
 * <p>Logback and Log4j 2 print the same order with the {@code %rEx} conversion word; this is for code that formats
 * exceptions itself, or logs through {@code java.util.logging}.
 */
public final class RootCauseFirst {

    private RootCauseFirst() {
    }

    /** The throwable with its causes and suppressed exceptions, root cause first, ending with a line break. */
    public static String format(Throwable throwable) {
        StringBuilder out = new StringBuilder();
        append(out, throwable, new StackTraceElement[0], "", "", Collections.newSetFromMap(new IdentityHashMap<>()));
        return out.toString();
    }

    /**
     * Appends {@code outermost} and its causes, root cause first, each line indented by {@code indent}, the first one
     * also headed by {@code prefix}. The frames that the outermost one shares with {@code enclosing} are left out.
     */
    private static void append(StringBuilder out, Throwable outermost, StackTraceElement[] enclosing, String prefix,
                               String indent, Set<Throwable> seen) {
        List<Throwable> chain = new ArrayList<>();
        Throwable circular = null;
        for (Throwable t = outermost; t != null; t = t.getCause()) {
            if (!seen.add(t)) {
                circular = t;
                break;
            }
            chain.add(t);
        }
        String header = prefix;
        if (circular != null) {
            out.append(indent).append(header).append("[CIRCULAR REFERENCE: ").append(circular).append("]\n");
            header = "Wrapped by: ";
        }
        List<StackTraceElement[]> traces = new ArrayList<>();
        for (Throwable t : chain) {
            traces.add(t.getStackTrace());
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            Throwable t = chain.get(i);
            StackTraceElement[] trace = traces.get(i);
            int common = common(trace, i == 0 ? enclosing : traces.get(i - 1));
            out.append(indent).append(header).append(t).append('\n');
            for (int f = 0; f < trace.length - common; f++) {
                out.append(indent).append("\tat ").append(trace[f]).append('\n');
            }
            if (common > 0) {
                out.append(indent).append("\t... ").append(common).append(" common frames omitted\n");
            }
            for (Throwable suppressed : t.getSuppressed()) {
                append(out, suppressed, trace, "Suppressed: ", indent + "\t", seen);
            }
            header = "Wrapped by: ";
        }
    }

    /** How many frames at the bottom of {@code trace} equal those at the bottom of {@code enclosing}. */
    private static int common(StackTraceElement[] trace, StackTraceElement[] enclosing) {
        int m = trace.length - 1;
        int n = enclosing.length - 1;
        while (m >= 0 && n >= 0 && trace[m].equals(enclosing[n])) {
            m--;
            n--;
        }
        return trace.length - 1 - m;
    }
}
