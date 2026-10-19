package com.hafnium.stackaugmentor.instrument.advice;

import com.hafnium.stackaugmentor.instrument.SelectedParam;
import com.hafnium.stackaugmentor.instrument.ParamSelection;
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
 * Binds {@code @ParamArgs Object[]} to the selected parameter values of each instrumented method. The array is built
 * where the advice reads it, so only on the exception path.
 */
public final class ParamArgsMapping implements Advice.OffsetMapping.Factory<ParamArgs> {

    private static final TypeDescription.Generic OBJECT = TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(Object.class);

    private final ParamSelection parameters;

    public ParamArgsMapping(ParamSelection parameters) {
        this.parameters = parameters;
    }

    @Override
    public Class<ParamArgs> getAnnotationType() {
        return ParamArgs.class;
    }

    @Override
    public Advice.OffsetMapping make(ParameterDescription.InDefinedShape target,
                                     AnnotationDescription.Loadable<ParamArgs> annotation,
                                     AdviceType adviceType) {
        return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) -> {
            List<SelectedParam> selected = parameters.select(instrumentedType, instrumentedMethod);
            if (selected.isEmpty()) {
                return new Advice.OffsetMapping.Target.ForStackManipulation(NullConstant.INSTANCE);
            }
            List<StackManipulation> values = new ArrayList<>(selected.size());
            for (SelectedParam selectedParam : selected) {
                TypeDescription.Generic type = selectedParam.parameter().getType();
                values.add(new StackManipulation.Compound(
                        MethodVariableAccess.of(type).loadFrom(argumentHandler.argument(selectedParam.parameter().getOffset())),
                        assigner.assign(type, OBJECT, Assigner.Typing.DYNAMIC)));
            }
            return new Advice.OffsetMapping.Target.ForArray.ReadOnly(OBJECT, values);
        };
    }
}
