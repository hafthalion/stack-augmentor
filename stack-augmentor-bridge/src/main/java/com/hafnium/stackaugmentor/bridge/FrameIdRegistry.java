package com.hafnium.stackaugmentor.bridge;

/**
 * Registry mode storage: for each throwable, the rendered text of the frames that have ids.
 * The throwable's own stack trace is left unchanged; printers look the frames up here.
 */
public final class FrameIdRegistry {

    private static final WeakIdentityMap<Throwable, String[]> FRAMES = new WeakIdentityMap<>();

    private FrameIdRegistry() {
    }

    /**
     * Stores the rendered text for one frame.
     *
     * @param traceLength length of the throwable's stack trace
     * @param rendered    the frame text as it should be printed after {@code "at "}
     */
    public static void put(Throwable throwable, int index, int traceLength, String rendered) {
        FRAMES.compute(throwable, (t, frames) -> {
            String[] result = frames == null || frames.length != traceLength ? new String[traceLength] : frames;
            result[index] = rendered;
            return result;
        });
    }

    /**
     * Returns a copy of the rendered frames, indexed like {@link Throwable#getStackTrace()}, with
     * {@code null} for frames without ids, or {@code null} if nothing was recorded for this throwable.
     */
    public static String[] frames(Throwable throwable) {
        String[] frames = FRAMES.get(throwable);
        return frames == null ? null : frames.clone();
    }
}
