package com.hafnium.stackaugmentor.instrument.advice;

import com.hafnium.stackaugmentor.instrument.IdParameter;
import com.hafnium.stackaugmentor.instrument.IdParameters;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.constant.NullConstant;
import net.bytebuddy.implementation.bytecode.constant.TextConstant;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds {@code @IdArgNames String[]} to the labels of the values bound by {@link IdArgsMapping}, with {@code #} after
 * those of hashed values, see {@link IdParameter#encodedLabel()}.
 */
public final class IdArgNamesMapping implements Advice.OffsetMapping.Factory<IdArgNames> {

    private static final TypeDescription.Generic STRING = TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(String.class);

    private final IdParameters parameters;

    public IdArgNamesMapping(IdParameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public Class<IdArgNames> getAnnotationType() {
        return IdArgNames.class;
    }

    @Override
    public Advice.OffsetMapping make(ParameterDescription.InDefinedShape target,
                                     AnnotationDescription.Loadable<IdArgNames> annotation,
                                     AdviceType adviceType) {
        return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) -> {
            List<IdParameter> selected = parameters.select(instrumentedType, instrumentedMethod);
            if (selected.isEmpty()) {
                return new Advice.OffsetMapping.Target.ForStackManipulation(NullConstant.INSTANCE);
            }
            List<StackManipulation> labels = new ArrayList<>(selected.size());
            for (IdParameter idParameter : selected) {
                labels.add(new TextConstant(idParameter.encodedLabel()));
            }
            return new Advice.OffsetMapping.Target.ForArray.ReadOnly(STRING, labels);
        };
    }
}
