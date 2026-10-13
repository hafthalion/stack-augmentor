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
 * Binds {@code @IdReceiver Object} to {@code this} and {@code @IdReceiverKey String} to the key of the deciding
 * {@code [augment.receiver]} entry in the methods whose frames show receiver ids ({@link TypeMatching#receiverEntry}),
 * and both to {@code null} in the others, e.g. the methods that no entry's {@code ::} names, which are instrumented for
 * their parameter ids only.
 */
public final class IdReceiverMapping {

    private IdReceiverMapping() {
    }

    /** The binding of {@code @IdReceiver}. */
    public static Advice.OffsetMapping.Factory<IdReceiver> receiver(TypeMatching matching) {
        return new Advice.OffsetMapping.Factory<>() {
            @Override
            public Class<IdReceiver> getAnnotationType() {
                return IdReceiver.class;
            }

            @Override
            public Advice.OffsetMapping make(ParameterDescription.InDefinedShape target,
                                             AnnotationDescription.Loadable<IdReceiver> annotation,
                                             AdviceType adviceType) {
                return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) ->
                        new Advice.OffsetMapping.Target.ForStackManipulation(matching.receiverIds(instrumentedType, instrumentedMethod)
                                ? MethodVariableAccess.loadThis()
                                : NullConstant.INSTANCE);
            }
        };
    }

    /** The binding of {@code @IdReceiverKey}. */
    public static Advice.OffsetMapping.Factory<IdReceiverKey> key(TypeMatching matching) {
        return new Advice.OffsetMapping.Factory<>() {
            @Override
            public Class<IdReceiverKey> getAnnotationType() {
                return IdReceiverKey.class;
            }

            @Override
            public Advice.OffsetMapping make(ParameterDescription.InDefinedShape target,
                                             AnnotationDescription.Loadable<IdReceiverKey> annotation,
                                             AdviceType adviceType) {
                return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) -> {
                    AugmentorConfig.ClassEntry entry = matching.receiverEntry(instrumentedType, instrumentedMethod);
                    return new Advice.OffsetMapping.Target.ForStackManipulation(entry != null
                            ? new TextConstant(entry.key())
                            : NullConstant.INSTANCE);
                };
            }
        };
    }
}
