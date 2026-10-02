package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.instrument.advice.ConstructorExit;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.ids.IdResolver;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hafnium.stackaugmentor.instrument.ClassEntries.firstInHierarchy;
import static com.hafnium.stackaugmentor.instrument.IdParameters.annotation;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

/** Decides which types and methods get the exit advice. */
public final class TypeMatching {

    /**
     * ByteBuddy's package is joined at runtime: the agent's shadow jar relocation rewrites the string constant
     * {@code "net.bytebuddy"} to the relocated package, which would leave the application's own ByteBuddy instrumentable.
     */
    private static final List<String> IGNORED_PACKAGES = List.of(
            "java", "javax", "jdk", "sun", "com.sun", "kotlin", String.join(".", "net", "bytebuddy"), "com.hafnium.stackaugmentor");

    /**
     * Types that the agent and the build plugin never instrument, whatever the configuration: synthetic types, and those
     * of the JDK, Kotlin, ByteBuddy and stack-augmentor itself. Checked before {@link #instrument}.
     */
    public static final ElementMatcher.Junction<TypeDescription> IGNORED = ignored();

    private final IdParameters parameters;
    private final ClassEntries classEntries;

    public TypeMatching(AugmentorConfig config, IdParameters parameters) {
        this.parameters = parameters;
        this.classEntries = new ClassEntries(config);
    }

    public boolean instrument(TypeDescription type) {
        if (type.isAnnotation()) {
            return false;
        }
        // The receiver ids and the parameter ids are configured independently; either one needs the advice.
        boolean instrument = receiverRelevant(type) || hasIdParameters(type);
        if (Log.isDebug()) {
            for (String message : parameters.unmatchedEntries(type)) {
                Log.debug(() -> message);
            }
            logIgnoredAnnotations(type);
        }
        return instrument;
    }

    /** Whether the class name is in a package of {@link #IGNORED}, without describing the class. */
    public static boolean isIgnoredName(String className) {
        for (String prefix : IGNORED_PACKAGES) {
            if (className.startsWith(prefix) && className.length() > prefix.length() && className.charAt(prefix.length()) == '.') {
                return true;
            }
        }
        return false;
    }

    private static ElementMatcher.Junction<TypeDescription> ignored() {
        ElementMatcher.Junction<TypeDescription> ignored = isSynthetic();
        for (String prefix : IGNORED_PACKAGES) {
            ignored = ignored.or(nameStartsWith(prefix + "."));
        }
        return ignored;
    }

    /** One line for the debug log: why the type is instrumented, and which methods get which parameter ids. */
    public String describe(TypeDescription type, ElementMatcher<MethodDescription> methods) {
        ClassEntries.Deciding deciding = classEntries.decide(type);
        String reason;
        if (!receiverRelevant(type)) {
            reason = "parameter ids only";
        } else if (!deciding.annotations()) {
            reason = "receiver id from [augment.receiver] \"" + deciding.entry().key() + "\"";
        } else {
            reason = "receiver id from @StackTraceId (\"" + deciding.entry().key() + "\" = \"@\")";
        }
        ElementMatcher<MethodDescription> constructors = constructors(type);
        List<String> instrumented = new ArrayList<>();
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (!methods.matches(method) && !constructors.matches(method)) {
                continue;
            }
            List<IdParameter> params = parameters.select(type, method);
            instrumented.add(params.isEmpty()
                    ? method.getInternalName()
                    : method.getInternalName() + "{" + params.stream().map(IdParameter::label).collect(Collectors.joining(", ")) + "}");
        }
        return "instrumenting " + type.getName() + " (" + reason + "): " + String.join(", ", instrumented);
    }

    /**
     * Instance methods get a receiver id; other methods are only instrumented for their id parameters.
     *
     * <p>Only the methods that the type declares, checked by their name and descriptor: ByteBuddy may offer a
     * method of the type's class file as the method it resolves to. Kotlin compiles a class that inherits an
     * interface's default method with a bridge method of the same signature, which ByteBuddy offers as the default
     * method itself, so checking the offered method would instrument the bridge.
     */
    public ElementMatcher<MethodDescription> methods(TypeDescription type) {
        boolean receiver = receiverRelevant(type);
        Map<String, MethodDescription> declared = new HashMap<>();
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (isCandidate(method)) {
                declared.put(signature(method), method);
            }
        }
        return offered -> {
            MethodDescription method = declared.get(signature(offered));
            return method != null && ((receiver && !method.isStatic()) || !parameters.select(type, method).isEmpty());
        };
    }

    /**
     * Constructors are only instrumented for their id parameters, never for a receiver id: {@code this} is not usable
     * where they throw. See {@link ConstructorExit}.
     */
    public ElementMatcher<MethodDescription> constructors(TypeDescription type) {
        Map<String, MethodDescription> declared = new HashMap<>();
        for (MethodDescription constructor : type.getDeclaredMethods()) {
            if (isConstructorCandidate(constructor) && !parameters.select(type, constructor).isEmpty()) {
                declared.put(signature(constructor), constructor);
            }
        }
        return offered -> declared.containsKey(signature(offered));
    }

    private static String signature(MethodDescription method) {
        return method.getInternalName() + method.getDescriptor();
    }

    /**
     * Whether frames of this method show a receiver id: a method, not a constructor, that is not static, of a type whose
     * deciding {@code [augment.receiver]} entry gives ids. For the agent's live-stack mode, which reads the frames of
     * methods that it does not instrument.
     */
    public boolean receiverIds(TypeDescription type, MethodDescription method) {
        return isCandidate(method) && !method.isStatic() && receiverRelevant(type);
    }

    /** The id parameters that frames of this method or constructor show. For the agent's live-stack mode. */
    public List<IdParameter> idParameters(TypeDescription type, MethodDescription method) {
        return isCandidate(method) || isConstructorCandidate(method) ? parameters.select(type, method) : List.of();
    }

    private boolean hasIdParameters(TypeDescription type) {
        for (MethodDescription method : type.getDeclaredMethods()) {
            if ((isCandidate(method) || isConstructorCandidate(method)) && !parameters.select(type, method).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** For the debug log: the annotations of the type that are not used, and which table would enable them. */
    private void logIgnoredAnnotations(TypeDescription type) {
        if (!classEntries.annotationsUsed(type) && firstInHierarchy(type, TypeMatching::hasAnnotatedMember) != null) {
            ClassEntries.Deciding deciding = classEntries.decide(type);
            String why = deciding == null
                    ? "no [augment.receiver] entry applies"
                    : "its [augment.receiver] entry \"" + deciding.entry().key() + "\" is not \"@\"";
            Log.debug(() -> "ignoring @StackTraceId in " + type.getName() + ": " + why);
        }
        List<String> methods = new ArrayList<>();
        for (MethodDescription method : type.getDeclaredMethods()) {
            if ((isCandidate(method) || isConstructorCandidate(method)) && IdParameters.hasParameterAnnotations(method)
                    && !parameters.annotationsUsed(type, method)) {
                methods.add(method.getInternalName());
            }
        }
        if (!methods.isEmpty()) {
            methods.sort(null);
            Log.debug(() -> "ignoring the parameter annotations of " + String.join(", ", methods) + " in " + type.getName()
                    + ": no \"@\" entry in [augment.params] applies");
        }
    }

    /**
     * The deciding {@code [augment.receiver]} entry names a field or method, or it is {@code "@"} and the class it
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

    private static boolean isConstructorCandidate(MethodDescription method) {
        return method.isConstructor() && !method.isSynthetic();
    }

    /**
     * Has a receiver id source: a field or a usable method ({@link IdResolver#isUsableIdMethod}) with
     * {@code @StackTraceId}, as Kotlin properties have on their field.
     */
    private static boolean hasAnnotatedMember(TypeDescription type) {
        for (FieldDescription field : type.getDeclaredFields()) {
            if (!field.isStatic() && annotation(field.getDeclaredAnnotations(), IdResolver.STACK_TRACE_ID) != null) {
                return true;
            }
        }
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (method.isMethod() && IdResolver.isUsableIdMethod(method.isStatic(), method.getParameters().size(), method.isSynthetic())
                    && annotation(method.getDeclaredAnnotations(), IdResolver.STACK_TRACE_ID) != null) {
                return true;
            }
        }
        return false;
    }
}
