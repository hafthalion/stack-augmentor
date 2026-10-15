package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.ParamRef;
import com.hafnium.stackaugmentor.runtime.ids.IdResolver;
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
 * Finds the parameters whose values a method's frames show: those the first matching {@code [augment.params]} entry
 * selects, by name or index, or, without such an entry, those the method's {@code @AugmentedParam} and
 * {@code @AugmentedParams} annotations select, where {@code [augment.annotations]} selects the class and
 * {@code @Augmented} the method. Also whether their values are hashed: {@code #} after a name or index, or
 * {@code @AugmentedParam(secret = true)}. {@code [augment.receiver]} plays no part.
 */
public final class IdParameters {

    private final AugmentorConfig config;

    public IdParameters(AugmentorConfig config) {
        this.config = config;
    }

    /**
     * Whether the parameter annotations of this method are used: no {@code [augment.params]} entry matches it,
     * {@code @Augmented} selects it and {@code [augment.annotations]} its class.
     */
    boolean annotationsUsed(TypeDescription type, MethodDescription method) {
        return paramsEntry(type, method) == null && annotationsSelect(type, method);
    }

    /**
     * Whether {@code @Augmented} selects the method and {@code [augment.annotations]} the type. Without
     * {@code @Augmented}, the other annotations are not looked for.
     */
    boolean annotationsSelect(TypeDescription type, MethodDescription method) {
        return AugmentedSelection.augments(type, method) && config.readsAnnotations(type.getName(), supertypes(type)::contains);
    }

    /** The values of the first matching {@code [augment.params]} entry, {@code null} if none matches. */
    private List<ParamRef> paramsEntry(TypeDescription type, MethodDescription method) {
        return config.paramsEntry(type.getName(), supertypes(type)::contains, method.getInternalName());
    }

    /** The last type's supertype names: they are asked for once per method of a type, for keys with {@code +}. */
    private volatile ClassEntries.SupertypeNames lastSupertypes;

    private ClassEntries.SupertypeNames supertypes(TypeDescription type) {
        ClassEntries.SupertypeNames last = lastSupertypes;
        if (last == null || last.type() != type) {
            last = new ClassEntries.SupertypeNames(type);
            lastSupertypes = last;
        }
        return last;
    }

    /** Whether the method has parameter annotations: {@code @AugmentedParam}, or {@code @AugmentedParams} on it. */
    static boolean hasParameterAnnotations(MethodDescription method) {
        if (annotation(method.getDeclaredAnnotations(), IdResolver.AUGMENTED_PARAMS) != null) {
            return true;
        }
        for (ParameterDescription parameter : method.getParameters()) {
            if (annotation(parameter.getDeclaredAnnotations(), IdResolver.AUGMENTED_PARAM) != null) {
                return true;
            }
        }
        return false;
    }

    public List<IdParameter> select(TypeDescription type, MethodDescription method) {
        ParameterList<?> parameters = method.getParameters();
        // By index: whether the value is hashed. A parameter selected twice (by name and by index) is hashed if either hashes it.
        TreeMap<Integer, Boolean> selected = new TreeMap<>();
        List<ParamRef> refs = paramsEntry(type, method);
        if (refs == null) {
            if (annotationsSelect(type, method)) {
                annotated(method, selected);
            }
            refs = List.of();
        }
        for (ParamRef ref : refs) {
            switch (ref) {
                case ParamRef.Excluded excluded -> {
                    // "-": no parameters
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
     * The parameters the annotations select: all of them with {@code @AugmentedParams} on the method, and those with
     * {@code @AugmentedParam}. Hashed with {@code @AugmentedParam(secret = true)}, also
     * under {@code @AugmentedParams}.
     */
    private static void annotated(MethodDescription method, TreeMap<Integer, Boolean> selected) {
        ParameterList<?> parameters = method.getParameters();
        if (annotation(method.getDeclaredAnnotations(), IdResolver.AUGMENTED_PARAMS) != null) {
            for (ParameterDescription parameter : parameters) {
                selected.merge(parameter.getIndex(), false, Boolean::logicalOr);
            }
        }
        for (ParameterDescription parameter : parameters) {
            AnnotationDescription one = annotation(parameter.getDeclaredAnnotations(), IdResolver.AUGMENTED_PARAM);
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
     * Entries with wildcards or {@code +} are skipped: they are expected not to fit every class and method they match.
     */
    public List<String> unmatchedEntries(TypeDescription type) {
        List<String> messages = new ArrayList<>();
        for (Map.Entry<String, List<ParamRef>> params : config.methods().entrySet()) {
            String target = params.getKey();
            AugmentorConfig.EntryKey key = new AugmentorConfig.EntryKey(target);
            if (key.subclasses() || !key.className().equals(type.getName())) {
                continue;
            }
            String entry = "[augment.params] \"" + target + "\"";
            List<MethodDescription> methods = new ArrayList<>();
            for (String methodName : key.methods()) {
                if (AugmentorConfig.isPattern(methodName)) {
                    continue;
                }
                List<MethodDescription> named = new ArrayList<>();
                for (MethodDescription method : type.getDeclaredMethods()) {
                    if ((method.isMethod() || method.isConstructor()) && method.getInternalName().equals(methodName)) {
                        named.add(method);
                    }
                }
                if (named.isEmpty()) {
                    messages.add(entry + ": " + type.getName() + " has no method '" + methodName + "'");
                }
                for (MethodDescription method : named) {
                    if (!methods.contains(method)) {
                        methods.add(method);
                    }
                }
            }
            for (MethodDescription method : methods) {
                ParameterList<?> parameters = method.getParameters();
                String signature = method.getInternalName() + "(" + parameters.stream()
                        .map(it -> it.getType().asErasure().getSimpleName() + " " + it.getName())
                        .collect(Collectors.joining(", ")) + ")";
                for (ParamRef ref : params.getValue()) {
                    switch (ref) {
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
