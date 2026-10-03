package com.hafnium.stackaugmentor.instrument.live;

import com.hafnium.stackaugmentor.instrument.IdParameter;
import com.hafnium.stackaugmentor.instrument.TypeMatching;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.IdSpec;
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
 * For the live-stack mode: which ids the frames of a method show, decided by the same rules that decide what the agent
 * instruments ({@link TypeMatching}), applied to the loaded classes. Looked up once per class and method.
 *
 * <p>It runs while a throwable is created, so it only looks at a class with reflection once a configuration entry
 * names it: reflection may load other classes.
 */
public final class FrameSpecs {

    /** A parameter shown after the method name: where its value is among the frame's local variables, and its type. */
    record Param(String label, boolean hashed, int slot, Class<?> type) {
    }

    /** The ids of a method's frames: the receiver's (from the entry of {@code owner}, the declaring class) and parameters. */
    record Spec(boolean receiver, Class<?> owner, List<Param> params) {
    }

    /** Frames that show no ids. */
    static final Spec NONE = new Spec(false, Object.class, List.of());

    /** What is known about the frames of one class. */
    static final class ClassInfo {

        /** Whether it is a class loader: while one runs, a class may be being loaded. */
        final boolean loader;
        /** Whether an entry names the class, so that its frames may show ids. */
        final boolean relevant;
        /** By the JVM's object for the method ({@link LiveStackFrames#method}), which is unique per method. */
        private final Map<Object, Spec> byMethod = new ConcurrentHashMap<>();
        /** By the number the JVM's backtrace gives the method; null where not seen yet. */
        private volatile Spec[] byNumber = new Spec[0];

        ClassInfo(boolean loader, boolean relevant) {
            this.loader = loader;
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
            return new ClassInfo(ClassLoader.class.isAssignableFrom(type), relevant(type));
        }
    };

    public FrameSpecs(AugmentorConfig config, TypeMatching matching) {
        this.config = config;
        this.matching = matching;
    }

    ClassInfo of(Class<?> type) {
        return byClass.get(type);
    }

    /**
     * The ids that this frame of a class of {@code info} shows; needs {@link StackWalker.Option#RETAIN_CLASS_REFERENCE}.
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
            spec = named(type.getName(), name) ? spec(type, name, frame.getMethodType()) : NONE;
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
        AugmentorConfig.ClassEntry entry = config.classEntry(name);
        return entry != null && !(entry.spec() instanceof IdSpec.Excluded) || config.hasParamEntries(name);
    }

    /** Whether an entry of either table names the method's class, so that its frames may show ids. */
    private boolean named(String className, String methodName) {
        AugmentorConfig.ClassEntry entry = config.classEntry(className);
        return entry != null && !(entry.spec() instanceof IdSpec.Excluded) && !methodName.equals(AugmentorConfig.CONSTRUCTOR)
                || !config.paramRefs(className, methodName).isEmpty();
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
            boolean receiver = matching.receiverIds(typeDescription, method);
            List<IdParameter> selected = matching.idParameters(typeDescription, method);
            if (!receiver && selected.isEmpty()) {
                return NONE;
            }
            Class<?>[] parameterTypes = executable.getParameterTypes();
            List<Param> params = new ArrayList<>(selected.size());
            for (IdParameter id : selected) {
                ParameterDescription parameter = id.parameter();
                params.add(new Param(id.label(), id.hashed(), parameter.getOffset(), parameterTypes[parameter.getIndex()]));
            }
            Spec spec = new Spec(receiver, type, List.copyOf(params));
            if (Log.isDebug()) {
                Log.debug(() -> "live stack: frames of " + type.getName() + "." + name + " show "
                        + (receiver ? "the receiver id" : "no receiver id")
                        + (params.isEmpty() ? "" : ", parameters " + params.stream().map(Param::label).collect(Collectors.joining(", "))));
            }
            return spec;
        } catch (RuntimeException | LinkageError e) {
            Log.debug(() -> "live stack: cannot read " + type.getName() + "." + name + ", so its frames show no ids: " + e);
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
