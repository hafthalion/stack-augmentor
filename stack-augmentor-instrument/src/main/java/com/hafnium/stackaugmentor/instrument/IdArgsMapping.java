package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.instrument.advice.IdArgs;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.constant.NullConstant;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds {@code @IdArgs Object[]} to the id parameter values of each instrumented method. The array is built
 * where the advice reads it, so only on the exception path.
 */
public final class IdArgsMapping implements Advice.OffsetMapping.Factory<IdArgs> {

    private static final TypeDescription.Generic OBJECT = TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(Object.class);

    private final IdParameters parameters;

    public IdArgsMapping(IdParameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public Class<IdArgs> getAnnotationType() {
        return IdArgs.class;
    }

    @Override
    public Advice.OffsetMapping make(ParameterDescription.InDefinedShape target,
                                     AnnotationDescription.Loadable<IdArgs> annotation,
                                     AdviceType adviceType) {
        return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) -> {
            List<IdParameter> selected = parameters.select(instrumentedType, instrumentedMethod);
            if (selected.isEmpty()) {
                return new Advice.OffsetMapping.Target.ForStackManipulation(NullConstant.INSTANCE);
            }
            List<StackManipulation> values = new ArrayList<>(selected.size());
            for (IdParameter idParameter : selected) {
                TypeDescription.Generic type = idParameter.parameter().getType();
                values.add(new StackManipulation.Compound(
                        MethodVariableAccess.of(type).loadFrom(argumentHandler.argument(idParameter.parameter().getOffset())),
                        assigner.assign(type, OBJECT, Assigner.Typing.DYNAMIC)));
            }
            return new Advice.OffsetMapping.Target.ForArray.ReadOnly(OBJECT, values);
        };
    }
}
