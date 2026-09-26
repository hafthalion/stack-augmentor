package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.runtime.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.IdResolver;
import com.hafnium.stackaugmentor.runtime.Log;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.hafnium.stackaugmentor.instrument.IdParameters.annotation;

/** Decides which types and methods get the exit advice. */
public final class TypeMatching {

    private final AugmentorConfig config;
    private final IdParameters parameters;

    public TypeMatching(AugmentorConfig config, IdParameters parameters) {
        this.config = config;
        this.parameters = parameters;
    }

    public boolean instrument(TypeDescription type) {
        if (type.isAnnotation()) {
            return false;
        }
        boolean instrument = receiverRelevant(type) || hasIdParameters(type);
        if (Log.isDebug()) {
            for (String message : parameters.unmatchedEntries(type)) {
                Log.debug(() -> message);
            }
            if (!instrument && !config.honoursAnnotations(type.getName()) && usesAnnotations(type)) {
                Log.debug(() -> "ignoring @StackTraceId, @StackTraceParam and @StackTraceParams in " + type.getName()
                        + ": not in instrument.annotatedClasses "
                        + config.annotatedClasses());
            }
        }
        return instrument;
    }

    /** One line for the debug log: why the type is instrumented, and which methods get which parameter ids. */
    public String describe(TypeDescription type, ElementMatcher<MethodDescription> methods) {
        TypeDescription configured = firstInHierarchy(type, it -> config.ids().containsKey(it.getName()));
        String reason;
        if (configured != null) {
            reason = "receiver id from [instrument.classIds] \"" + configured.getName() + "\"";
        } else if (receiverRelevant(type)) {
            reason = "receiver id from @StackTraceId";
        } else {
            reason = "parameter ids only";
        }
        List<String> instrumented = new ArrayList<>();
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (!methods.matches(method)) {
                continue;
            }
            List<IdParameter> params = parameters.select(type, method);
            instrumented.add(params.isEmpty()
                    ? method.getInternalName()
                    : method.getInternalName() + "{" + params.stream().map(IdParameter::label).collect(Collectors.joining(", ")) + "}");
        }
        return "instrumenting " + type.getName() + " (" + reason + "): " + String.join(", ", instrumented);
    }

    /** Instance methods get a receiver id; other methods are only instrumented for their id parameters. */
    public ElementMatcher<MethodDescription> methods(TypeDescription type) {
        boolean receiver = receiverRelevant(type);
        return method -> isCandidate(method) && ((receiver && !method.isStatic()) || !parameters.select(type, method).isEmpty());
    }

    private boolean hasIdParameters(TypeDescription type) {
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (isCandidate(method) && !parameters.select(type, method).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Uses any of the annotations: for the debug message about annotations outside instrument.annotatedClasses. */
    private boolean usesAnnotations(TypeDescription type) {
        if (firstInHierarchy(type, TypeMatching::hasAnnotatedMember) != null
                || annotation(type.getDeclaredAnnotations(), IdResolver.STACK_TRACE_PARAMS) != null) {
            return true;
        }
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (annotation(method.getDeclaredAnnotations(), IdResolver.STACK_TRACE_PARAMS) != null
                    || hasAnnotatedParameter(method, IdResolver.STACK_TRACE_PARAM)) {
                return true;
            }
        }
        return false;
    }

    /** Configured in {@code [instrument.classIds]}, or annotated (possibly in a superclass) in an {@code instrument.annotatedClasses} package. */
    private boolean receiverRelevant(TypeDescription type) {
        return firstInHierarchy(type, it -> config.ids().containsKey(it.getName())) != null
                || (config.honoursAnnotations(type.getName()) && firstInHierarchy(type, TypeMatching::hasAnnotatedMember) != null);
    }

    private static boolean isCandidate(MethodDescription method) {
        return method.isMethod() && !method.isAbstract() && !method.isNative() && !method.isBridge() && !method.isSynthetic();
    }

    /** Has a receiver id source: a field, a method or (Kotlin) a primary-constructor property with {@code @StackTraceId}. */
    private static boolean hasAnnotatedMember(TypeDescription type) {
        for (FieldDescription field : type.getDeclaredFields()) {
            if (!field.isStatic() && annotation(field.getDeclaredAnnotations(), IdResolver.STACK_TRACE_ID) != null) {
                return true;
            }
        }
        for (MethodDescription method : type.getDeclaredMethods()) {
            if ((method.isMethod() && !method.isStatic() && annotation(method.getDeclaredAnnotations(), IdResolver.STACK_TRACE_ID) != null)
                    || (method.isConstructor() && hasAnnotatedParameter(method, IdResolver.STACK_TRACE_ID))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnnotatedParameter(MethodDescription method, String annotationName) {
        for (ParameterDescription parameter : method.getParameters()) {
            if (annotation(parameter.getDeclaredAnnotations(), annotationName) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first of the type and its superclasses that matches, stopping at {@code Object} or at a superclass that
     * cannot be resolved. Superclasses are only resolved as far as needed.
     */
    private static TypeDescription firstInHierarchy(TypeDescription type, Predicate<TypeDescription> predicate) {
        TypeDefinition current = type;
        while (current != null) {
            TypeDescription erasure = current.asErasure();
            if (erasure.represents(Object.class)) {
                return null;
            }
            if (predicate.test(erasure)) {
                return erasure;
            }
            try {
                current = current.getSuperClass();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
