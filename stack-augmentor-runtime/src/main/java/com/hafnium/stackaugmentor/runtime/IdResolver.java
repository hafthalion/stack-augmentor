package com.hafnium.stackaugmentor.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Turns objects into ids. The id source of each class is looked up once and cached. Every id is
 * converted to a String right away, so no references to live objects are kept.
 */
public final class IdResolver {

    /** Name of the id annotation. Matched by name, because the application may load its own copy of the API. */
    public static final String STACK_TRACE_ID = "com.hafnium.stackaugmentor.StackTraceId";

    /** Name of the annotation that selects one parameter. */
    public static final String STACK_TRACE_PARAM = "com.hafnium.stackaugmentor.StackTraceParam";

    /** Name of the annotation that selects all parameters of a method, or of the methods of a class. */
    public static final String STACK_TRACE_PARAMS = "com.hafnium.stackaugmentor.StackTraceParams";

    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n]+");

    private sealed interface Source {

        String name();

        String description();

        Object read(Object target) throws Exception;
    }

    private record FieldSource(Field field, String name) implements Source {

        @Override
        public String description() {
            return "field " + field.getDeclaringClass().getName() + "." + field.getName() + ", label '" + name + "'";
        }

        @Override
        public Object read(Object target) throws IllegalAccessException {
            return field.get(target);
        }
    }

    private record MethodSource(Method method, String name) implements Source {

        @Override
        public String description() {
            return "method " + method.getDeclaringClass().getName() + "." + method.getName() + "(), label '" + name + "'";
        }

        @Override
        public Object read(Object target) throws ReflectiveOperationException {
            return method.invoke(target);
        }
    }

    /** Text of a value, computed by code that may throw. */
    @FunctionalInterface
    private interface TextSupplier {
        String get() throws Exception;
    }

    private final AugmentorConfig config;

    private final ClassValue<Source> sources = new ClassValue<>() {
        @Override
        protected Source computeValue(Class<?> type) {
            Source source = findSource(type);
            if (Log.isDebug()) {
                Log.debug(() -> "id source of " + type.getName() + ": " + (source != null ? source.description() : "none"));
            }
            return source;
        }
    };

    public IdResolver(AugmentorConfig config) {
        this.config = config;
    }

    /** The id of the object a frame runs on, or {@code null} if its class has no configured or annotated id source. */
    public NamedId receiverId(Object target) {
        Source source = sources.get(target.getClass());
        return source != null ? new NamedId(source.name(), read(source, target)) : null;
    }

    /** The id of an argument: its class's id source if it has one, otherwise its text. */
    public String paramId(Object value) {
        if (value == null) {
            return "null";
        }
        boolean plain = value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?>;
        Source source = plain ? null : sources.get(value.getClass());
        if (source != null) {
            return read(source, value);
        }
        return guarded(() -> {
            if (value.getClass().isArray()) {
                String text = Arrays.deepToString(new Object[] {value});
                return text.substring(1, text.length() - 1);
            }
            return value.toString();
        });
    }

    private String read(Source source, Object target) {
        return guarded(() -> String.valueOf(source.read(target)));
    }

    private String guarded(TextSupplier block) {
        String text;
        try {
            text = block.get();
        } catch (Throwable e) {
            return "?";
        }
        return sanitize(text != null ? text : "null");
    }

    private String sanitize(String text) {
        String singleLine = text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 ? LINE_BREAKS.matcher(text).replaceAll(" ") : text;
        int maxIdLength = config.maxIdLength();
        return singleLine.length() > maxIdLength ? singleLine.substring(0, maxIdLength - 1) + "…" : singleLine;
    }

    private Source findSource(Class<?> type) {
        // 1. External configuration, which also covers subclasses of a configured class.
        for (Class<?> owner = type; isInHierarchy(owner); owner = owner.getSuperclass()) {
            IdSpec spec = config.ids().get(owner.getName());
            if (spec != null) {
                return sourceFor(owner, spec);
            }
        }
        // 2. @StackTraceId on a field, a no-argument method or (Kotlin) a primary constructor property,
        //    for classes in the instrument.annotatedClasses packages.
        if (!config.honoursAnnotations(type.getName())) {
            return null;
        }
        for (Class<?> owner = type; isInHierarchy(owner); owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                Annotation annotation = idAnnotation(field);
                if (!Modifier.isStatic(field.getModifiers()) && annotation != null) {
                    return fieldSource(field, label(annotation, field.getName()));
                }
            }
            for (Method method : owner.getDeclaredMethods()) {
                Annotation annotation = idAnnotation(method);
                if (!Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0 && !method.isSynthetic()
                        && annotation != null) {
                    return methodSource(method, label(annotation, method.getName()));
                }
            }
            for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
                for (Parameter parameter : constructor.getParameters()) {
                    Annotation annotation = idAnnotation(parameter);
                    if (annotation == null || !parameter.isNamePresent()) {
                        continue;
                    }
                    Field field = instanceField(owner, parameter.getName());
                    if (field != null) {
                        return fieldSource(field, label(annotation, field.getName()));
                    }
                }
            }
        }
        return null;
    }

    private Source sourceFor(Class<?> owner, IdSpec spec) {
        Source source = null;
        for (Class<?> type = owner; isInHierarchy(type); type = type.getSuperclass()) {
            if (spec instanceof IdSpec.MethodSpec) {
                Method method = noArgumentMethod(type, spec.memberName());
                if (method != null) {
                    source = methodSource(method, method.getName());
                    break;
                }
            } else {
                Field field = instanceField(type, spec.memberName());
                if (field != null) {
                    source = fieldSource(field, field.getName());
                    break;
                }
            }
        }
        if (source == null) {
            String member = spec instanceof IdSpec.MethodSpec ? "method " + spec.memberName() + "()" : "field " + spec.memberName();
            Log.warn("[instrument.classIds] \"" + owner.getName() + "\": no " + member + " found");
        }
        return source;
    }

    private static Field instanceField(Class<?> type, String name) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().equals(name) && !Modifier.isStatic(field.getModifiers())) {
                return field;
            }
        }
        return null;
    }

    private static Method noArgumentMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }

    private static Source fieldSource(Field field, String name) {
        if (field.trySetAccessible()) {
            return new FieldSource(field, name);
        }
        Log.warn("cannot access " + field);
        return null;
    }

    private static Source methodSource(Method method, String name) {
        if (method.trySetAccessible()) {
            return new MethodSource(method, name);
        }
        Log.warn("cannot access " + method);
        return null;
    }

    /** The class and its superclasses, stopping at {@code Object}. */
    private static boolean isInHierarchy(Class<?> type) {
        return type != null && type != Object.class;
    }

    public static Annotation idAnnotation(AnnotatedElement element) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            if (annotation.annotationType().getName().equals(STACK_TRACE_ID)) {
                return annotation;
            }
        }
        return null;
    }

    /** The annotation's {@code name} if set, otherwise {@code defaultLabel}. */
    public static String label(Annotation annotation, String defaultLabel) {
        if (annotation == null) {
            return defaultLabel;
        }
        try {
            if (annotation.annotationType().getMethod("name").invoke(annotation) instanceof String name && !name.isEmpty()) {
                return name;
            }
        } catch (ReflectiveOperationException e) {
            // no usable name: use the default
        }
        return defaultLabel;
    }
}
