package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.runtime.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.IdResolver;
import com.hafnium.stackaugmentor.runtime.Log;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.hafnium.stackaugmentor.instrument.ClassEntries.firstInHierarchy;
import static com.hafnium.stackaugmentor.instrument.IdParameters.annotation;

/** Decides which types and methods get the exit advice. */
public final class TypeMatching {

    private final IdParameters parameters;
    private final ClassEntries classEntries;

    public TypeMatching(AugmentorConfig config, IdParameters parameters) {
        this.parameters = parameters;
        this.classEntries = parameters.classEntries();
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
            String ignored = ignoredAnnotations(type);
            if (ignored != null) {
                ClassEntries.Deciding deciding = classEntries.decide(type);
                String why = deciding != null && deciding.excluded()
                        ? "[instrument.classes] \"" + deciding.entry().key() + "\" = \"-\" ignores the class"
                        : "no \"@\" entry in [instrument.classes] or [instrument.methods] applies";
                Log.debug(() -> "ignoring " + ignored + " in " + type.getName() + ": " + why);
            }
        }
        return instrument;
    }

    /** One line for the debug log: why the type is instrumented, and which methods get which parameter ids. */
    public String describe(TypeDescription type, ElementMatcher<MethodDescription> methods) {
        ClassEntries.Deciding deciding = classEntries.decide(type);
        String reason;
        if (deciding != null && deciding.excluded()) {
            reason = "parameter ids only, the receiver is ignored by [instrument.classes] \"" + deciding.entry().key() + "\" = \"-\"";
        } else if (deciding != null && !deciding.annotations()) {
            reason = "receiver id from [instrument.classes] \"" + deciding.entry().key() + "\"";
        } else if (receiverRelevant(type)) {
            reason = "receiver id from @StackTraceId (\"" + deciding.entry().key() + "\" = \"@\")";
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

    /**
     * For the debug log: the annotations of the type that are not used, e.g. {@code @StackTraceId and the parameter
     * annotations of run, stop}, or {@code null} if there are none.
     */
    private String ignoredAnnotations(TypeDescription type) {
        boolean receiverIgnored = !classEntries.annotationsUsed(type) && firstInHierarchy(type, TypeMatching::hasAnnotatedMember) != null;
        List<String> methods = new ArrayList<>();
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (isCandidate(method) && IdParameters.hasParameterAnnotations(type, method) && !parameters.annotationsUsed(type, method)) {
                methods.add(method.getInternalName());
            }
        }
        methods.sort(null);
        String parameterAnnotations = methods.isEmpty() ? null : "the parameter annotations of " + String.join(", ", methods);
        if (receiverIgnored) {
            return parameterAnnotations != null ? "@StackTraceId and " + parameterAnnotations : "@StackTraceId";
        }
        return parameterAnnotations;
    }

    /**
     * The deciding {@code [instrument.classes]} entry names a field or method, or it is {@code "@"} and the class it
     * matched or one of its superclasses has an {@code @StackTraceId} member. Not with {@code "-"}.
     */
    private boolean receiverRelevant(TypeDescription type) {
        ClassEntries.Deciding deciding = classEntries.decide(type);
        if (deciding == null || deciding.excluded()) {
            return false;
        }
        return !deciding.annotations() || firstInHierarchy(deciding.owner(), TypeMatching::hasAnnotatedMember) != null;
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
}
