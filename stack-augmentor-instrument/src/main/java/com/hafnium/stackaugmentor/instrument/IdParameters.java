package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.runtime.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.IdResolver;
import com.hafnium.stackaugmentor.runtime.ParamRef;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.method.ParameterList;
import net.bytebuddy.description.type.TypeDescription;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Finds the id parameters of a method: annotated with {@code @StackTraceId}, or listed in the {@code [instrument.methodParams]} config table. */
public final class IdParameters {

    private final AugmentorConfig config;

    public IdParameters(AugmentorConfig config) {
        this.config = config;
    }

    public List<IdParameter> select(TypeDescription type, MethodDescription method) {
        ParameterList<?> parameters = method.getParameters();
        TreeMap<Integer, String> labels = new TreeMap<>();
        if (config.honoursAnnotations(type.getName())) {
            for (ParameterDescription parameter : parameters) {
                AnnotationDescription annotation = idAnnotation(parameter.getDeclaredAnnotations());
                if (annotation == null) {
                    continue;
                }
                String label = annotationLabel(annotation);
                labels.put(parameter.getIndex(), label != null ? label : parameter.getName());
            }
        }
        for (ParamRef ref : config.paramRefs(type.getName(), method.getInternalName())) {
            ParameterDescription parameter = switch (ref) {
                case ParamRef.ByName byName -> named(parameters, byName.name());
                case ParamRef.ByIndex byIndex -> byIndex.index() < parameters.size() ? parameters.get(byIndex.index()) : null;
            };
            if (parameter != null) {
                labels.putIfAbsent(parameter.getIndex(), parameter.getName());
            }
        }
        // Without the MethodParameters attribute, ByteBuddy names parameters arg0, arg1, ...
        List<IdParameter> selected = new ArrayList<>(labels.size());
        for (Map.Entry<Integer, String> label : labels.entrySet()) {
            selected.add(new IdParameter(parameters.get(label.getKey()), label.getValue()));
        }
        return selected;
    }

    /** Debug messages for {@code [instrument.methodParams]} entries of this type that match no method or parameter. */
    public List<String> unmatchedEntries(TypeDescription type) {
        List<String> messages = new ArrayList<>();
        for (Map.Entry<String, List<ParamRef>> params : config.params().entrySet()) {
            String target = params.getKey();
            int dot = target.lastIndexOf('.');
            if (!(dot >= 0 ? target.substring(0, dot) : target).equals(type.getName())) {
                continue;
            }
            String methodName = target.substring(dot + 1);
            String entry = "[instrument.methodParams] \"" + target + "\"";
            List<MethodDescription> methods = new ArrayList<>();
            for (MethodDescription method : type.getDeclaredMethods()) {
                if (method.isMethod() && method.getInternalName().equals(methodName)) {
                    methods.add(method);
                }
            }
            if (methods.isEmpty()) {
                messages.add(entry + ": " + type.getName() + " has no method '" + methodName + "'");
                continue;
            }
            for (MethodDescription method : methods) {
                ParameterList<?> parameters = method.getParameters();
                String signature = methodName + "(" + parameters.stream()
                        .map(it -> it.getType().asErasure().getSimpleName() + " " + it.getName())
                        .collect(Collectors.joining(", ")) + ")";
                for (ParamRef ref : params.getValue()) {
                    switch (ref) {
                        case ParamRef.ByIndex byIndex -> {
                            if (byIndex.index() >= parameters.size()) {
                                messages.add(entry + ": no parameter #" + byIndex.index() + " in " + signature);
                            }
                        }
                        case ParamRef.ByName byName -> {
                            if (named(parameters, byName.name()) != null) {
                                continue;
                            }
                            if (parameters.stream().anyMatch(it -> !it.isNamed())) {
                                messages.add(entry + ": cannot find '" + byName.name() + "' in " + signature
                                        + ", the class has no parameter names (compile with -parameters, or use an index)");
                            } else {
                                messages.add(entry + ": no parameter '" + byName.name() + "' in " + signature);
                            }
                        }
                    }
                }
            }
        }
        return messages;
    }

    private static ParameterDescription named(ParameterList<?> parameters, String name) {
        for (ParameterDescription parameter : parameters) {
            if (parameter.isNamed() && parameter.getName().equals(name)) {
                return parameter;
            }
        }
        return null;
    }

    private static String annotationLabel(AnnotationDescription annotation) {
        try {
            String name = annotation.getValue("name").resolve(String.class);
            return name.isEmpty() ? null : name;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static AnnotationDescription idAnnotation(AnnotationList annotations) {
        for (AnnotationDescription annotation : annotations) {
            if (annotation.getAnnotationType().getName().equals(IdResolver.STACK_TRACE_ID)) {
                return annotation;
            }
        }
        return null;
    }
}
