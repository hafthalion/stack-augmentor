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

/**
 * Finds the id parameters of a method: those the {@code [augment.methods]} config table selects, by name, index,
 * {@code "*"}, or {@code "@"} for the method's {@code @StackTraceParam} and {@code @StackTraceParams} annotations.
 * {@code [augment.classes]} plays no part.
 */
public final class IdParameters {

    private final AugmentorConfig config;

    public IdParameters(AugmentorConfig config) {
        this.config = config;
    }

    /** Whether the parameter annotations of this method are used: an {@code [augment.methods]} {@code "@"} entry applies. */
    boolean annotationsUsed(TypeDescription type, MethodDescription method) {
        return hasAnnotationsRef(paramRefs(type, method));
    }

    private List<ParamRef> paramRefs(TypeDescription type, MethodDescription method) {
        return config.paramRefs(type.getName(), method.getInternalName());
    }

    private static boolean hasAnnotationsRef(List<ParamRef> refs) {
        for (ParamRef ref : refs) {
            if (ref instanceof ParamRef.Annotations) {
                return true;
            }
        }
        return false;
    }

    /** Whether the method has parameter annotations: {@code @StackTraceParam}, or {@code @StackTraceParams} on it or its type. */
    static boolean hasParameterAnnotations(TypeDescription type, MethodDescription method) {
        if (annotation(method.getDeclaredAnnotations(), IdResolver.STACK_TRACE_PARAMS) != null
                || (annotation(type.getDeclaredAnnotations(), IdResolver.STACK_TRACE_PARAMS) != null && !method.getParameters().isEmpty())) {
            return true;
        }
        for (ParameterDescription parameter : method.getParameters()) {
            if (annotation(parameter.getDeclaredAnnotations(), IdResolver.STACK_TRACE_PARAM) != null) {
                return true;
            }
        }
        return false;
    }

    public List<IdParameter> select(TypeDescription type, MethodDescription method) {
        ParameterList<?> parameters = method.getParameters();
        TreeMap<Integer, String> labels = new TreeMap<>();
        List<ParamRef> refs = paramRefs(type, method);
        if (hasAnnotationsRef(refs)) {
            // @StackTraceParams on the method, or on the class declaring it: all parameters.
            if (annotation(method.getDeclaredAnnotations(), IdResolver.STACK_TRACE_PARAMS) != null
                    || annotation(type.getDeclaredAnnotations(), IdResolver.STACK_TRACE_PARAMS) != null) {
                for (ParameterDescription parameter : parameters) {
                    labels.put(parameter.getIndex(), parameter.getName());
                }
            }
            // @StackTraceParam: this parameter, with its label.
            for (ParameterDescription parameter : parameters) {
                AnnotationDescription annotation = annotation(parameter.getDeclaredAnnotations(), IdResolver.STACK_TRACE_PARAM);
                if (annotation == null) {
                    continue;
                }
                String label = annotationLabel(annotation);
                labels.put(parameter.getIndex(), label != null ? label : parameter.getName());
            }
        }
        for (ParamRef ref : refs) {
            switch (ref) {
                case ParamRef.Annotations annotations -> {
                    // Handled above, with the annotations.
                }
                case ParamRef.Excluded excluded -> {
                    // paramRefs stops at "-".
                }
                case ParamRef.All all -> {
                    for (ParameterDescription parameter : parameters) {
                        labels.putIfAbsent(parameter.getIndex(), parameter.getName());
                    }
                }
                case ParamRef.ByName byName -> {
                    ParameterDescription parameter = named(parameters, byName.name());
                    if (parameter != null) {
                        labels.putIfAbsent(parameter.getIndex(), parameter.getName());
                    }
                }
                case ParamRef.ByIndex byIndex -> {
                    if (byIndex.index() < parameters.size()) {
                        labels.putIfAbsent(byIndex.index(), parameters.get(byIndex.index()).getName());
                    }
                }
            }
        }
        // Without the MethodParameters attribute, ByteBuddy names parameters arg0, arg1, ...
        List<IdParameter> selected = new ArrayList<>(labels.size());
        for (Map.Entry<Integer, String> label : labels.entrySet()) {
            selected.add(new IdParameter(parameters.get(label.getKey()), label.getValue()));
        }
        return selected;
    }

    /**
     * Debug messages for {@code [augment.methods]} entries of this type that match no method or parameter.
     * Entries with wildcards are skipped: they are expected not to fit every class and method they match.
     */
    public List<String> unmatchedEntries(TypeDescription type) {
        List<String> messages = new ArrayList<>();
        for (Map.Entry<String, List<ParamRef>> params : config.methods().entrySet()) {
            String target = params.getKey();
            if (AugmentorConfig.isPattern(target)) {
                continue;
            }
            int dot = target.lastIndexOf('.');
            if (!(dot >= 0 ? target.substring(0, dot) : target).equals(type.getName())) {
                continue;
            }
            String methodName = target.substring(dot + 1);
            String entry = "[augment.methods] \"" + target + "\"";
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
                        case ParamRef.All all -> {
                        }
                        case ParamRef.Annotations annotations -> {
                        }
                        case ParamRef.Excluded excluded -> {
                        }
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

    /** The annotation with this class name, matched by name because the application may load its own copy of the API. */
    static AnnotationDescription annotation(AnnotationList annotations, String name) {
        for (AnnotationDescription annotation : annotations) {
            if (annotation.getAnnotationType().getName().equals(name)) {
                return annotation;
            }
        }
        return null;
    }
}
