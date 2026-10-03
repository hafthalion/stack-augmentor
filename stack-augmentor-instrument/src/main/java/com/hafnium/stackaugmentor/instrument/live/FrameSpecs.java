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

    /** For the classes none of whose frames show ids. */
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

    /** Whether any method or constructor that the class declares shows ids. */
    private final ClassValue<Boolean> withIds = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (byClass.get(type) == IRRELEVANT) {
                return false;
            }
            try {
                for (Method method : type.getDeclaredMethods()) {
                    if (of(type, method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes())) != NONE) {
                        return true;
                    }
                }
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    if (of(type, AugmentorConfig.CONSTRUCTOR, MethodType.methodType(void.class, constructor.getParameterTypes())) != NONE) {
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException | LinkageError e) {
                return true;
            }
        }
    };

    public FrameSpecs(AugmentorConfig config, TypeMatching matching) {
        this.config = config;
        this.matching = matching;
    }

    /**
     * Whether frames of the class may show ids: an entry names it, and it declares a method or constructor that shows
     * ids. Needs only the class, so a walk without method information ({@link StackWalker.Option#DROP_METHOD_INFO})
     * can tell. Looks at the class with reflection the first time.
     */
    boolean mayShowIds(Class<?> type) {
        return withIds.get(type);
    }

    /** Whether an entry names the class; cheaper than {@link #mayShowIds}, and without reflection. */
    boolean isNamed(Class<?> type) {
        return byClass.get(type) != IRRELEVANT;
    }

    /** The ids that this frame shows; needs {@link StackWalker.Option#RETAIN_CLASS_REFERENCE}. */
    Spec of(StackWalker.StackFrame frame) {
        Class<?> type = frame.getDeclaringClass();
        if (byClass.get(type) == IRRELEVANT) {
            return NONE;
        }
        return of(type, frame.getMethodName(), frame.getMethodType());
    }

    private Spec of(Class<?> type, String name, MethodType methodType) {
        Map<MethodKey, Spec> methods = byClass.get(type);
        if (methods == IRRELEVANT) {
            return NONE;
        }
        MethodKey method = new MethodKey(name, methodType);
        Spec spec = methods.get(method);
        if (spec == null) {
            spec = named(type.getName(), name) ? spec(type, name, methodType) : NONE;
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
