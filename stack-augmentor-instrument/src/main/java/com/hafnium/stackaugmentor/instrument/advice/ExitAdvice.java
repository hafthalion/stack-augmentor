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

    // The parameters' current values, not copies made on entry: a reassigned parameter shows its new value, and a
    // Kotlin suspend function resumed after a suspension shows the values it restored, not the placeholders it was
    // called with. Not copying also keeps normal returns free of cost.
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class, backupArguments = false)
    public static void exit(@Receiver Object self,
                            @Advice.Thrown Throwable thrown,
                            @ReceiverKey String receiverKey,
                            @Advice.Origin Class<?> owner,
                            @Advice.Origin("#m") String method,
                            @ParamArgs Object[] args,
                            @ParamNames String[] paramNames) {
        if (thrown != null) {
            Dispatch.onThrow(self, thrown, receiverKey, owner, method, args, paramNames);
        }
    }
}
