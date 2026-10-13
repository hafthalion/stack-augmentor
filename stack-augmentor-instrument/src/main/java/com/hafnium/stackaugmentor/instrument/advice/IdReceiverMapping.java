package com.hafnium.stackaugmentor.instrument.advice;

import com.hafnium.stackaugmentor.instrument.TypeMatching;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.implementation.bytecode.constant.NullConstant;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;

/**
 * Binds {@code @IdReceiver Object} to {@code this} in the methods whose frames show receiver ids
 * ({@link TypeMatching#receiverIds}), and to {@code null} in the others, e.g. the methods that {@code ::} in
 * {@code [augment.receiver]} leaves out, which are instrumented for their parameter ids only.
 */
public final class IdReceiverMapping implements Advice.OffsetMapping.Factory<IdReceiver> {

    private final TypeMatching matching;

    public IdReceiverMapping(TypeMatching matching) {
        this.matching = matching;
    }

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
}
