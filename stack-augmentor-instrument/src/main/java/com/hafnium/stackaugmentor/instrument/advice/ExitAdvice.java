package com.hafnium.stackaugmentor.instrument.advice;

import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the end of every instrumented method. Written in Java so that no Kotlin runtime
 * calls end up in the instrumented classes.
 *
 * <p>ByteBuddy replaces each read of an advice parameter with the bytecode that produces its value,
 * so the receiver, the argument array and the labels are only loaded inside the {@code if} branch:
 * a normal return costs a single null check.
 */
public final class ExitAdvice {

    private ExitAdvice() {
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Receiver Object self,
                            @Advice.Thrown Throwable thrown,
                            @ReceiverKey String receiverKey,
                            @Advice.Origin("#t") String owner,
                            @Advice.Origin("#m") String method,
                            @ParamArgs Object[] args,
                            @ParamNames String[] paramNames) {
        if (thrown != null) {
            Dispatch.onThrow(self, thrown, receiverKey, owner, method, args, paramNames);
        }
    }
}
