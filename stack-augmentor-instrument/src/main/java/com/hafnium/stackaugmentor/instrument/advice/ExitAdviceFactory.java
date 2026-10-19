package com.hafnium.stackaugmentor.instrument.advice;

import com.hafnium.stackaugmentor.instrument.ParamSelection;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import net.bytebuddy.asm.Advice;

/** Creates the exit advice shared by the Java agent and the build plugin. */
public final class ExitAdviceFactory {

    private ExitAdviceFactory() {
    }

    /**
     * The exit advice, with the {@code @Receiver}/{@code @ReceiverKey} bindings for these receiver members and the {@code @ParamArgs}/{@code @ParamNames}
     * bindings for these selected parameters.
     */
    public static Advice create(TypeMatching matching, ParamSelection parameters) {
        return Advice.withCustomMapping()
                .bind(ReceiverMapping.receiver(matching))
                .bind(ReceiverMapping.key(matching))
                .bind(new ParamArgsMapping(parameters))
                .bind(new ParamNamesMapping(parameters))
                .to(ExitAdvice.class);
    }
}
