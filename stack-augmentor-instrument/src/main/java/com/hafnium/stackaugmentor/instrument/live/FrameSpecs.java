package com.hafnium.stackaugmentor.instrument.live;

import com.hafnium.stackaugmentor.instrument.SelectedParam;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.ReceiverSpec;
import com.hafnium.stackaugmentor.runtime.values.ValueResolver;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * For the live-stack mode: which values the frames of a method show, decided by the same rules that decide what the agent
 * instruments ({@link TypeMatching}), applied to the loaded classes. Looked up once per class and method.
 *
 * <p>It runs while a throwable is created, so it only looks at a class with reflection once a configuration entry
 * names it: reflection may load other classes.
 */
public final class FrameSpecs {

    /** A parameter shown after the method name: where its value is among the frame's local variables, and its type. */
    record Param(String label, boolean hashed, int slot, Class<?> type) {
    }

    /**
     * What a method's frames show: the receiver members (from the {@code [augment.receiver]} entry with key
     * {@code receiverKey} for {@code owner}, the declaring class; {@code null} for none) and parameters.
     */
    record Spec(String receiverKey, Class<?> owner, List<Param> params) {

        boolean receiver() {
            return receiverKey != null;
        }
    }

    /** Frames that are not augmented. */
    static final Spec NONE = new Spec(null, Object.class, List.of());

    /** For the classes none of whose frames are augmented. */
    private static final Map<MethodKey, Spec> IRRELEVANT = Map.of();

    private record MethodKey(String name, MethodType type) {
    }

    private final AugmentorConfig config;
    private final TypeMatching matching;

    private final ClassValue<Map<MethodKey, Spec>> byClass = new ClassValue<>() {
        @Override
        protected Map<MethodKey, Spec> computeValue(Class<?> type) {
            return relevant(type) ? new ConcurrentHashMap<>() : IRRELEVANT;
        }
    };

    /**
     * Whether the class or one of its methods or constructors has {@code @Augmented}: without it, the class's
     * annotations are ignored, so a class without it needs no look at its methods.
     */
    private final ClassValue<Boolean> augmented = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                if (hasAugmented(type.getDeclaredAnnotations())) {
                    return true;
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (hasAugmented(method.getDeclaredAnnotations())) {
                        return true;
                    }
                }
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    if (hasAugmented(constructor.getDeclaredAnnotations())) {
                        return true;
                    }
                }
                return false;
            } catch (LinkageError | RuntimeException e) {
                return true; // left to spec(), which reports it
            }
        }
    };

    /** By name: the application may load its own copy of the API. */
    private static boolean hasAugmented(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().getName().equals(ValueResolver.AUGMENTED)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the annotations may apply to the class: it has {@code @Augmented}. */
    private boolean readsAnnotations(Class<?> type) {
        return augmented.get(type);
    }

    public FrameSpecs(AugmentorConfig config, TypeMatching matching) {
        this.config = config;
        this.matching = matching;
    }

    /** What this frame shows; needs {@link StackWalker.Option#RETAIN_CLASS_REFERENCE}. */
    Spec of(StackWalker.StackFrame frame) {
        Class<?> type = frame.getDeclaringClass();
        Map<MethodKey, Spec> methods = byClass.get(type);
        if (methods == IRRELEVANT) {
            return NONE;
        }
        MethodKey method = new MethodKey(frame.getMethodName(), frame.getMethodType());
        Spec spec = methods.get(method);
        if (spec == null) {
            spec = named(type, method.name()) ? spec(type, method.name(), method.type()) : NONE;
            methods.putIfAbsent(method, spec);
        }
        return spec;
    }

    /**
     * Not the types that the agent never instruments, those of the JDK, Kotlin, ByteBuddy and stack-augmentor itself,
     * and only those that an entry names.
     */
    private boolean relevant(Class<?> type) {
        if (type.isHidden() || type.isArray() || type.isPrimitive() || type.isSynthetic() || type.isAnnotation()) {
            return false;
        }
        String name = type.getName();
        if (TypeMatching.isIgnoredName(name)) {
            return false;
        }
        return config.classEntries(type).stream().anyMatch(entry -> !(entry.spec() instanceof ReceiverSpec.Excluded))
                || config.hasParamEntries(name, supertype -> AugmentorConfig.isA(type, supertype))
                || readsAnnotations(type);
    }

    /**
     * Whether the annotations or an entry of either table may apply to the method, so that its frames may show
     * values. By name only, so the annotations are left open: {@link #spec} decides for the method itself.
     */
    private boolean named(Class<?> type, String methodName) {
        return !methodName.equals(AugmentorConfig.CONSTRUCTOR) && config.classEntries(type).stream()
                        .anyMatch(entry -> !(entry.spec() instanceof ReceiverSpec.Excluded) && entry.appliesTo(methodName))
                || !config.paramRefs(type.getName(), supertype -> AugmentorConfig.isA(type, supertype), methodName).isEmpty()
                || readsAnnotations(type);
    }

    private Spec spec(Class<?> type, String name, MethodType methodType) {
        try {
            Executable executable = executable(type, name, methodType);
            if (executable == null) {
                return NONE;
            }
            TypeDescription typeDescription = TypeDescription.ForLoadedType.of(type);
            MethodDescription method = executable instanceof Method m
                    ? new MethodDescription.ForLoadedMethod(m)
                    : new MethodDescription.ForLoadedConstructor((Constructor<?>) executable);
            AugmentorConfig.ReceiverEntry entry = matching.receiverEntry(typeDescription, method);
            boolean receiver = entry != null;
            List<SelectedParam> selected = matching.selectedParams(typeDescription, method);
            if (!receiver && selected.isEmpty()) {
                return NONE;
            }
            Class<?>[] parameterTypes = executable.getParameterTypes();
            List<Param> params = new ArrayList<>(selected.size());
            for (SelectedParam selectedParam : selected) {
                ParameterDescription parameter = selectedParam.parameter();
                params.add(new Param(selectedParam.label(), selectedParam.hashed(), parameter.getOffset(), parameterTypes[parameter.getIndex()]));
            }
            Spec spec = new Spec(receiver ? entry.key() : null, type, List.copyOf(params));
            if (Log.isDebug()) {
                Log.debug(() -> "live stack: frames of " + type.getName() + "." + name + " show "
                        + (receiver ? "the receiver members" : "no receiver members")
                        + (params.isEmpty() ? "" : ", parameters " + params.stream().map(Param::label).collect(Collectors.joining(", "))));
            }
            return spec;
        } catch (RuntimeException | LinkageError e) {
            Log.debug(() -> "live stack: cannot read " + type.getName() + "." + name + ", so its frames are not augmented: " + e);
            return NONE;
        }
    }

    /** The method or constructor that the type declares with this name and type; null for a static initializer. */
    private static Executable executable(Class<?> type, String name, MethodType methodType) throws LinkageError {
        Class<?>[] parameterTypes = methodType.parameterArray();
        if (name.equals("<init>")) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (Arrays.equals(constructor.getParameterTypes(), parameterTypes)) {
                    return constructor;
                }
            }
            return null;
        }
        // By name, parameter types and return type: a bridge method differs from the method it calls only in the latter.
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getReturnType() == methodType.returnType()
                    && Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                return method;
            }
        }
        return null;
    }
}
