package com.hafnium.stackaugmentor.instrument.advice;

import com.hafnium.stackaugmentor.instrument.IdParameters;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import net.bytebuddy.asm.Advice;

/** Creates the exit advice shared by the Java agent and the build plugin. */
public final class ExitAdviceFactory {

    private ExitAdviceFactory() {
    }

    /**
     * The exit advice, with the {@code @IdReceiver}/{@code @IdReceiverKey} bindings for these receiver ids and the {@code @IdArgs}/{@code @IdArgNames}
     * bindings for these id parameters.
     */
    public static Advice create(TypeMatching matching, IdParameters parameters) {
        return Advice.withCustomMapping()
                .bind(IdReceiverMapping.receiver(matching))
                .bind(IdReceiverMapping.key(matching))
                .bind(new IdArgsMapping(parameters))
                .bind(new IdArgNamesMapping(parameters))
                .to(ExitAdvice.class);
    }
}
