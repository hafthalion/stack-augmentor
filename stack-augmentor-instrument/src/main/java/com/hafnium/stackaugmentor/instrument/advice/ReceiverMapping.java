package com.hafnium.stackaugmentor.instrument.advice;

import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.implementation.bytecode.constant.NullConstant;
import net.bytebuddy.implementation.bytecode.constant.TextConstant;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;

/**
 * Binds {@code @Receiver Object} to {@code this} and {@code @ReceiverKey String} to the key of the deciding
 * {@code [augment.receiver]} entry in the methods whose frames show receiver members ({@link TypeMatching#receiverEntry}),
 * and both to {@code null} in the others, e.g. the methods that no entry's {@code ::} names, which are instrumented for
 * their parameter values only.
 */
public final class ReceiverMapping {

    private ReceiverMapping() {
    }

    /** The binding of {@code @Receiver}. */
    public static Advice.OffsetMapping.Factory<Receiver> receiver(TypeMatching matching) {
        return new Advice.OffsetMapping.Factory<>() {
            @Override
            public Class<Receiver> getAnnotationType() {
                return Receiver.class;
            }

            @Override
            public Advice.OffsetMapping make(ParameterDescription.InDefinedShape target,
                                             AnnotationDescription.Loadable<Receiver> annotation,
                                             AdviceType adviceType) {
                return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) ->
                        new Advice.OffsetMapping.Target.ForStackManipulation(matching.showsReceiver(instrumentedType, instrumentedMethod)
                                ? MethodVariableAccess.loadThis()
                                : NullConstant.INSTANCE);
            }
        };
    }

    /** The binding of {@code @ReceiverKey}. */
    public static Advice.OffsetMapping.Factory<ReceiverKey> key(TypeMatching matching) {
        return new Advice.OffsetMapping.Factory<>() {
            @Override
            public Class<ReceiverKey> getAnnotationType() {
                return ReceiverKey.class;
            }

            @Override
            public Advice.OffsetMapping make(ParameterDescription.InDefinedShape target,
                                             AnnotationDescription.Loadable<ReceiverKey> annotation,
                                             AdviceType adviceType) {
                return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) -> {
                    AugmentorConfig.ReceiverEntry entry = matching.receiverEntry(instrumentedType, instrumentedMethod);
                    return new Advice.OffsetMapping.Target.ForStackManipulation(entry != null
                            ? new TextConstant(entry.key())
                            : NullConstant.INSTANCE);
                };
            }
        };
    }
}
