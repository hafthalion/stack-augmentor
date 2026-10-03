package com.hafnium.stackaugmentor.instrument.live;

/**
 * HotSpot's {@code Throwable.backtrace}: the frames that the VM recorded, from which it later creates the stack trace
 * array, one element per frame in the same order. A chain of chunks, each an {@code Object[]} of {@code [methods
 * (short[]), bcis (int[]), classes (Object[]), names, continuations, next chunk, hidden]} for up to 32 frames; a
 * {@code null} class ends the frames. Unsupported by the JDK: {@link #check} checks that it still looks like this.
 */
final class Backtrace {

    private static final int METHODS = 0;
    private static final int BCIS = 1;
    private static final int CLASSES = 2;
    private static final int NEXT = 5;
    private static final int SIZE = 7;

    /** The frames, flattened: the class, the method's number within it, and the bci of each. */
    final Class<?>[] classes;
    final short[] methods;
    final int[] bcis;
    final int length;

    private Backtrace(int capacity) {
        classes = new Class<?>[capacity];
        methods = new short[capacity];
        bcis = new int[capacity];
        length = capacity;
    }

    /** The first {@code count} frames of the backtrace; fewer if it has fewer. */
    static Backtrace of(Object backtrace, int count) {
        Backtrace frames = new Backtrace(count);
        int index = 0;
        for (Object[] chunk = (Object[]) backtrace; chunk != null && index < count; chunk = (Object[]) chunk[NEXT]) {
            Object[] classes = (Object[]) chunk[CLASSES];
            int n = Math.min(classes.length, count - index);
            System.arraycopy(classes, 0, frames.classes, index, n);
            System.arraycopy((short[]) chunk[METHODS], 0, frames.methods, index, n);
            int[] bcis = (int[]) chunk[BCIS];
            for (int k = 0; k < n; k++) {
                // The bci in the high 16 bits, the class's version in the low ones.
                frames.bcis[index + k] = bcis[k] >>> 16;
            }
            index += n;
        }
        return frames;
    }

    /** The classes of the chunk, or null for the end. */
    static Object[] classes(Object chunk) {
        return chunk == null ? null : (Object[]) ((Object[]) chunk)[CLASSES];
    }

    static short[] methods(Object chunk) {
        return (short[]) ((Object[]) chunk)[METHODS];
    }

    static Object next(Object chunk) {
        return ((Object[]) chunk)[NEXT];
    }

    /**
     * Checks the layout with a backtrace recorded in a method of {@code top}.
     *
     * @return null, or what is not as expected
     */
    static String check(Object backtrace, Class<?> top) {
        if (backtrace instanceof Object[] chunk && chunk.length == SIZE && chunk[METHODS] instanceof short[] methods
                && chunk[BCIS] instanceof int[] bcis && chunk[CLASSES] instanceof Object[] classes
                && methods.length == classes.length && bcis.length == classes.length && classes.length > 0 && classes[0] == top
                && (chunk[NEXT] == null || chunk[NEXT] instanceof Object[])) {
            return null;
        }
        return "the backtrace of throwables is not laid out as expected";
    }
}
