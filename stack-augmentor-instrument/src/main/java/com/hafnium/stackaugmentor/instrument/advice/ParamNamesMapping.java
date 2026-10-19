package com.hafnium.stackaugmentor.instrument.advice;

import com.hafnium.stackaugmentor.instrument.SelectedParam;
import com.hafnium.stackaugmentor.instrument.ParamSelection;
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
 * Binds {@code @ParamNames String[]} to the labels of the values bound by {@link ParamArgsMapping}, with {@code #} after
 * those of hashed values, see {@link SelectedParam#encodedLabel()}.
 */
public final class ParamNamesMapping implements Advice.OffsetMapping.Factory<ParamNames> {

    private static final TypeDescription.Generic STRING = TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(String.class);

    private final ParamSelection parameters;

    public ParamNamesMapping(ParamSelection parameters) {
        this.parameters = parameters;
    }

    @Override
    public Class<ParamNames> getAnnotationType() {
        return ParamNames.class;
    }

    @Override
    public Advice.OffsetMapping make(ParameterDescription.InDefinedShape target,
                                     AnnotationDescription.Loadable<ParamNames> annotation,
                                     AdviceType adviceType) {
        return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) -> {
            List<SelectedParam> selected = parameters.select(instrumentedType, instrumentedMethod);
            if (selected.isEmpty()) {
                return new Advice.OffsetMapping.Target.ForStackManipulation(NullConstant.INSTANCE);
            }
            List<StackManipulation> labels = new ArrayList<>(selected.size());
            for (SelectedParam selectedParam : selected) {
                labels.add(new TextConstant(selectedParam.encodedLabel()));
            }
            return new Advice.OffsetMapping.Target.ForArray.ReadOnly(STRING, labels);
        };
    }
}
