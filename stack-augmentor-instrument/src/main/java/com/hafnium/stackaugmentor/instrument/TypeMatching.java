package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.instrument.advice.ConstructorExit;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.ReceiverSpec;
import com.hafnium.stackaugmentor.runtime.values.ValueResolver;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hafnium.stackaugmentor.instrument.ClassEntries.firstInHierarchy;
import static com.hafnium.stackaugmentor.instrument.ParamSelection.annotation;
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

    private final AugmentorConfig config;
    private final ParamSelection parameters;
    private final ClassEntries classEntries;

    public TypeMatching(AugmentorConfig config, ParamSelection parameters) {
        this.config = config;
        this.parameters = parameters;
        this.classEntries = new ClassEntries(config);
    }

    public boolean instrument(TypeDescription type) {
        if (type.isAnnotation()) {
            return false;
        }
        // The receiver members and the parameter values are configured independently; either one needs the advice.
        boolean instrument = hasReceiverMethods(type) || hasSelectedParams(type);
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

    /** One line for the debug log: why the type is instrumented, and which methods show which parameters. */
    public String describe(TypeDescription type, ElementMatcher<MethodDescription> methods) {
        Set<AugmentorConfig.ReceiverEntry> used = new HashSet<>();
        for (MethodDescription method : type.getDeclaredMethods()) {
            AugmentorConfig.ReceiverEntry entry = receiverEntry(type, method);
            if (entry != null && methods.matches(method)) {
                used.add(entry);
            }
        }
        List<String> receivers = new ArrayList<>(classEntries.entries(type).stream().filter(used::contains)
                .map(entry -> "[augment.receiver] \"" + entry.key() + "\"")
                .toList());
        if (used.contains(AugmentorConfig.ANNOTATION_ENTRY)) {
            receivers.add("@AugmentedReceiver");
        }
        String reason = receivers.isEmpty() ? "parameter values only" : "receiver members from " + String.join(", ", receivers);
        ElementMatcher<MethodDescription> constructors = constructors(type);
        List<String> instrumented = new ArrayList<>();
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (!methods.matches(method) && !constructors.matches(method)) {
                continue;
            }
            List<SelectedParam> params = parameters.select(type, method);
            instrumented.add(params.isEmpty()
                    ? method.getInternalName()
                    : method.getInternalName() + "{" + params.stream().map(SelectedParam::label).collect(Collectors.joining(", ")) + "}");
        }
        return "instrumenting " + type.getName() + " (" + reason + "): " + String.join(", ", instrumented);
    }

    /**
     * Instance methods show receiver members; other methods are only instrumented for their selected parameters.
     *
     * <p>Only the methods that the type declares, checked by their name and descriptor: ByteBuddy may offer a
     * method of the type's class file as the method it resolves to. Kotlin compiles a class that inherits an
     * interface's default method with a bridge method of the same signature, which ByteBuddy offers as the default
     * method itself, so checking the offered method would instrument the bridge.
     */
    public ElementMatcher<MethodDescription> methods(TypeDescription type) {
        Map<String, MethodDescription> declared = new HashMap<>();
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (isCandidate(method)) {
                declared.put(signature(method), method);
            }
        }
        return offered -> {
            MethodDescription method = declared.get(signature(offered));
            return method != null && (showsReceiver(type, method) || !parameters.select(type, method).isEmpty());
        };
    }

    /**
     * Constructors are only instrumented for their selected parameters, never for receiver members: {@code this} is not
     * usable where they throw. See {@link ConstructorExit}.
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
     * Whether frames of this method show receiver members: a method, not a constructor, that is not static, of a type
     * whose deciding {@code [augment.receiver]} entry names receiver members for this method. For the agent's live-stack
     * mode, which reads the frames of methods that it does not instrument.
     */
    public boolean showsReceiver(TypeDescription type, MethodDescription method) {
        return receiverEntry(type, method) != null;
    }

    /**
     * The deciding receiver entry of the frames of this method if they show receiver members, else {@code null}, for a
     * method, not a constructor, that is not static: the first {@code [augment.receiver]} entry that matches it, unless
     * it is {@code "-"}; without one, {@link AugmentorConfig#ANNOTATION_ENTRY} if {@code [augment.annotations]} selects
     * the type, {@code @Augmented} the method, and the type or one of its superclasses has an
     * {@code @AugmentedReceiver} member.
     */
    public AugmentorConfig.ReceiverEntry receiverEntry(TypeDescription type, MethodDescription method) {
        if (!isCandidate(method) || method.isStatic()) {
            return null;
        }
        ClassEntries.Deciding deciding = classEntries.decide(type, method);
        if (deciding != null) {
            return deciding.excluded() ? null : deciding.entry();
        }
        return parameters.annotationsSelect(type, method) && annotated(type) ? AugmentorConfig.ANNOTATION_ENTRY : null;
    }

    /** The type weakly, as its description holds its class loader, as in {@link ClassEntries}. */
    private record Annotated(WeakReference<TypeDescription> type, boolean annotated) {
    }

    /** The last answer of {@link #annotated}: it is asked for once per method of a type. */
    private volatile Annotated lastAnnotated;

    /** Whether the type or one of its superclasses has an {@code @AugmentedReceiver} member. */
    private boolean annotated(TypeDescription type) {
        Annotated last = lastAnnotated;
        if (last == null || last.type().get() != type) {
            last = new Annotated(new WeakReference<>(type), firstInHierarchy(type, TypeMatching::hasAnnotatedMember) != null);
            lastAnnotated = last;
        }
        return last.annotated();
    }

    /** Whether a method the type declares shows receiver members. */
    private boolean hasReceiverMethods(TypeDescription type) {
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (showsReceiver(type, method)) {
                return true;
            }
        }
        return false;
    }

    /** The selected parameters that frames of this method or constructor show. For the agent's live-stack mode. */
    public List<SelectedParam> selectedParams(TypeDescription type, MethodDescription method) {
        return isCandidate(method) || isConstructorCandidate(method) ? parameters.select(type, method) : List.of();
    }

    private boolean hasSelectedParams(TypeDescription type) {
        for (MethodDescription method : type.getDeclaredMethods()) {
            if ((isCandidate(method) || isConstructorCandidate(method)) && !parameters.select(type, method).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** For the debug log: the annotations of the type that are not used, and why. */
    private void logIgnoredAnnotations(TypeDescription type) {
        boolean readsAnnotations = config.readsAnnotations(type.getName(), new ClassEntries.SupertypeNames(type)::contains);
        String notRead = "no [augment.annotations] entry that is true matches it";
        boolean receiverUsed = false;
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (receiverEntry(type, method) == AugmentorConfig.ANNOTATION_ENTRY) {
                receiverUsed = true;
                break;
            }
        }
        if (!receiverUsed && firstInHierarchy(type, TypeMatching::hasAnnotatedMember) != null) {
            String why = readsAnnotations ? "[augment.receiver] entries or @Augmented decide for all its methods" : notRead;
            Log.debug(() -> "ignoring @AugmentedReceiver in " + type.getName() + ": " + why);
        }
        List<String> methods = new ArrayList<>();
        for (MethodDescription method : type.getDeclaredMethods()) {
            if ((isCandidate(method) || isConstructorCandidate(method)) && ParamSelection.hasParameterAnnotations(method)
                    && !parameters.annotationsUsed(type, method)) {
                methods.add(method.getInternalName());
            }
        }
        if (!methods.isEmpty()) {
            methods.sort(null);
            String why = readsAnnotations ? "an [augment.params] entry or @Augmented decides for them" : notRead;
            Log.debug(() -> "ignoring the parameter annotations of " + String.join(", ", methods) + " in " + type.getName()
                    + ": " + why);
        }
    }

    private static boolean isCandidate(MethodDescription method) {
        return method.isMethod() && !method.isAbstract() && !method.isNative() && !method.isBridge() && !method.isSynthetic();
    }

    private static boolean isConstructorCandidate(MethodDescription method) {
        return method.isConstructor() && !method.isSynthetic();
    }

    /**
     * Has a receiver member: a field or a usable method ({@link ValueResolver#isUsableReceiverMethod}) with
     * {@code @AugmentedReceiver}, as Kotlin properties have on their field.
     */
    private static boolean hasAnnotatedMember(TypeDescription type) {
        for (FieldDescription field : type.getDeclaredFields()) {
            if (!field.isStatic() && annotation(field.getDeclaredAnnotations(), ValueResolver.AUGMENTED_RECEIVER) != null) {
                return true;
            }
        }
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (method.isMethod() && ValueResolver.isUsableReceiverMethod(method.isStatic(), method.getParameters().size(), method.isSynthetic())
                    && annotation(method.getDeclaredAnnotations(), ValueResolver.AUGMENTED_RECEIVER) != null) {
                return true;
            }
        }
        return false;
    }
}
