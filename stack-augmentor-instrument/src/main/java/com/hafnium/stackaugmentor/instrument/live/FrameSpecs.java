package com.hafnium.stackaugmentor.instrument.live;

import com.hafnium.stackaugmentor.instrument.SelectedParam;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.ReceiverSpec;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;

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

    /** What is known about the frames of one class. */
    static final class ClassInfo {

        /** Whether an entry or the annotations may apply to the class, so that its frames may be augmented. */
        final boolean relevant;
        /** By the JVM's object for the method ({@link LiveStackFrames#method}), which is unique per method. */
        private final Map<Object, Spec> byMethod = new ConcurrentHashMap<>();
        /** By the number the JVM's backtrace gives the method; null where not seen yet. */
        private volatile Spec[] byNumber = new Spec[0];

        ClassInfo(boolean relevant) {
            this.relevant = relevant;
        }

        /** The spec of the method with this backtrace number: {@link #NONE}, or null if not known yet. */
        Spec byNumber(int number) {
            if (!relevant) {
                return NONE;
            }
            Spec[] specs = byNumber;
            return number < specs.length ? specs[number] : null;
        }

        void learn(int number, Spec spec) {
            Spec[] specs = byNumber;
            if (number < specs.length && specs[number] == spec) {
                return;
            }
            synchronized (this) {
                Spec[] copy = Arrays.copyOf(byNumber, Math.max(byNumber.length, number + 1));
                copy[number] = spec;
                byNumber = copy;
            }
        }
    }

    private final AugmentorConfig config;
    private final TypeMatching matching;

    private final ClassValue<ClassInfo> byClass = new ClassValue<>() {
        @Override
        protected ClassInfo computeValue(Class<?> type) {
            return new ClassInfo(relevant(type));
        }
    };

    /**
     * Whether the class has {@code @Augmented}, or is a Kotlin file class: without it, the class's annotations are
     * ignored, and its methods are not looked at.
     */
    private final ClassValue<Boolean> augmented = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return TypeMatching.readsAnnotations(TypeDescription.ForLoadedType.of(type));
            } catch (LinkageError | RuntimeException e) {
                return true; // left to spec(), which reports it
            }
        }
    };

    /** Whether the annotations may apply to the class: it has {@code @Augmented}, or is a Kotlin file class. */
    private boolean readsAnnotations(Class<?> type) {
        return augmented.get(type);
    }

    public FrameSpecs(AugmentorConfig config, TypeMatching matching) {
        this.config = config;
        this.matching = matching;
    }

    ClassInfo of(Class<?> type) {
        return byClass.get(type);
    }

    /**
     * What this frame of a class of {@code info} shows; needs {@link StackWalker.Option#RETAIN_CLASS_REFERENCE}.
     * {@code method} is the JVM's object for the frame's method: only the first frame of a method needs its name and type.
     */
    Spec of(StackWalker.StackFrame frame, ClassInfo info, Object method) {
        if (!info.relevant) {
            return NONE;
        }
        Spec spec = info.byMethod.get(method);
        if (spec == null) {
            Class<?> type = frame.getDeclaringClass();
            String name = frame.getMethodName();
            spec = named(type, name) ? spec(type, name, frame.getMethodType()) : NONE;
            info.byMethod.putIfAbsent(method, spec);
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
