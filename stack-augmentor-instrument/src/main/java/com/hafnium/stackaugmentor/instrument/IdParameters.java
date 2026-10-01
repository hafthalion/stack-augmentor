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
 * Finds the id parameters of a method: those the {@code [augment.params]} config table selects, by name, index,
 * or {@code "@"} for the method's {@code @StackTraceParam} and {@code @StackTraceParams} annotations, and whether their
 * values are hashed: {@code #} after a name or index, or {@code @StackTraceParam(secret = true)}. {@code [augment.receiver]}
 * plays no part.
 */
public final class IdParameters {

    private final AugmentorConfig config;

    public IdParameters(AugmentorConfig config) {
        this.config = config;
    }

    /** Whether the parameter annotations of this method are used: an {@code [augment.params]} {@code "@"} entry applies. */
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

    /** Whether the method has parameter annotations: {@code @StackTraceParam}, or {@code @StackTraceParams} on it. */
    static boolean hasParameterAnnotations(MethodDescription method) {
        if (annotation(method.getDeclaredAnnotations(), IdResolver.STACK_TRACE_PARAMS) != null) {
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
        // By index: whether the value is hashed. A parameter selected twice (by name and by index) is hashed if either hashes it.
        TreeMap<Integer, Boolean> selected = new TreeMap<>();
        for (ParamRef ref : paramRefs(type, method)) {
            switch (ref) {
                case ParamRef.Annotations annotations -> annotated(method, selected);
                case ParamRef.Excluded excluded -> {
                    // paramRefs returns no refs for "-".
                }
                case ParamRef.ByName byName -> {
                    ParameterDescription parameter = named(parameters, byName.name());
                    if (parameter != null) {
                        selected.merge(parameter.getIndex(), byName.hashed(), Boolean::logicalOr);
                    }
                }
                case ParamRef.ByIndex byIndex -> {
                    if (byIndex.index() < parameters.size()) {
                        selected.merge(byIndex.index(), byIndex.hashed(), Boolean::logicalOr);
                    }
                }
            }
        }
        // The label is the compiled parameter name; without the MethodParameters attribute (javac without -parameters),
        // ByteBuddy names parameters arg0, arg1, ...
        List<IdParameter> result = new ArrayList<>(selected.size());
        for (Map.Entry<Integer, Boolean> entry : selected.entrySet()) {
            ParameterDescription parameter = parameters.get(entry.getKey());
            result.add(new IdParameter(parameter, parameter.getName(), entry.getValue()));
        }
        return result;
    }

    /**
     * The parameters the annotations select: all of them with {@code @StackTraceParams} on the method, and those with
     * {@code @StackTraceParam}. Hashed with {@code @StackTraceParam(secret = true)}, also
     * under {@code @StackTraceParams}.
     */
    private static void annotated(MethodDescription method, TreeMap<Integer, Boolean> selected) {
        ParameterList<?> parameters = method.getParameters();
        if (annotation(method.getDeclaredAnnotations(), IdResolver.STACK_TRACE_PARAMS) != null) {
            for (ParameterDescription parameter : parameters) {
                selected.merge(parameter.getIndex(), false, Boolean::logicalOr);
            }
        }
        for (ParameterDescription parameter : parameters) {
            AnnotationDescription one = annotation(parameter.getDeclaredAnnotations(), IdResolver.STACK_TRACE_PARAM);
            if (one != null) {
                selected.merge(parameter.getIndex(), secret(one), Boolean::logicalOr);
            }
        }
    }

    /** The annotation's {@code secret} value, read by name: the application may load its own, older copy of the API. */
    private static boolean secret(AnnotationDescription annotation) {
        for (MethodDescription.InDefinedShape property : annotation.getAnnotationType().getDeclaredMethods()) {
            if (property.getName().equals("secret")) {
                try {
                    return Boolean.TRUE.equals(annotation.getValue(property).resolve());
                } catch (RuntimeException e) {
                    // Not resolvable, e.g. an API copy whose annotation declares no default: shown as text.
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Debug messages for {@code [augment.params]} entries of this type that match no method or parameter.
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
            String entry = "[augment.params] \"" + target + "\"";
            List<MethodDescription> methods = new ArrayList<>();
            for (MethodDescription method : type.getDeclaredMethods()) {
                if ((method.isMethod() || method.isConstructor()) && method.getInternalName().equals(methodName)) {
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
