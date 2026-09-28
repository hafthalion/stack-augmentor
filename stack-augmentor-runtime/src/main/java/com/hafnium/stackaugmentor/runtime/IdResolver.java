package com.hafnium.stackaugmentor.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns objects into ids: receivers through the id source of a class, looked up once and cached, and arguments
 * through their text. Every id is converted to a String right away, so no references to live objects are kept.
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

    /** {@link #receiverId(Object, String)} for a method that the object's own class declares. */
    public NamedId receiverId(Object target) {
        return receiverId(target, target.getClass().getName());
    }

    /**
     * The id of the object a frame runs on, or {@code null} if there is no id source. The id source is that of the
     * class declaring the frame's method, not of the object's runtime class: a subclass, e.g. a proxy or a mock, shows
     * the id of the class whose method it runs, and a default method shows the id of its interface's entry. Its member
     * is read from the object.
     *
     * @param declaringClass the name of the class that declares the method
     */
    public NamedId receiverId(Object target, String declaringClass) {
        Source source = sources.get(declaringClass(target.getClass(), declaringClass));
        return source != null ? new NamedId(source.name(), read(source, target)) : null;
    }

    /**
     * The class or interface named {@code name} among the type, its superclasses and the interfaces they implement,
     * directly or not; the type itself if there is none.
     */
    private static Class<?> declaringClass(Class<?> type, String name) {
        for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
            for (Class<?> implemented : candidate.getInterfaces()) {
                for (Class<?> iface : lookupOrder(implemented)) {
                    if (iface.getName().equals(name)) {
                        return iface;
                    }
                }
            }
        }
        return type;
    }

    /**
     * The id of an argument: its text, from {@code toString()} (arrays with their elements). The receiver id sources
     * of its class are not used.
     */
    public String paramId(Object value) {
        return guarded(() -> text(value));
    }

    /** A receiver id is shown like an argument, e.g. an array with its elements. */
    private String read(Source source, Object target) {
        return guarded(() -> text(source.read(target)));
    }

    private static String text(Object value) {
        if (value == null) {
            return "null";
        }
        if (value.getClass().isArray()) {
            String text = Arrays.deepToString(new Object[] {value});
            return text.substring(1, text.length() - 1);
        }
        return value.toString();
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

    /**
     * One line, capped at {@code maxIdLength}. Parentheses become braces: IDEs find the file and line of a frame by its
     * {@code (File.java:12)}, which parentheses in an id, e.g. from a data class's {@code toString()}, would confuse.
     */
    private String sanitize(String text) {
        String braced = oneLineBraced(text);
        int maxIdLength = config.maxIdLength();
        return braced.length() > maxIdLength ? braced.substring(0, maxIdLength - 1) + "…" : braced;
    }

    /**
     * The deciding {@code [augment.receiver]} entry: the most specific entry that matches the class's own name; entries
     * of superclasses do not apply. Its field or method, or with {@code "@"} its {@code @StackTraceId}, is looked up in
     * the class and its superclasses, or in an interface and the interfaces it extends. Without a deciding entry, or with {@code "-"}, the class has no id source, even
     * if it is annotated.
     */
    private Source findSource(Class<?> type) {
        AugmentorConfig.ClassEntry entry = config.classEntry(type.getName());
        return entry != null ? sourceFor(type, entry) : null;
    }

    /**
     * {@code @StackTraceId} on a field or a no-argument method; a Kotlin property annotated in the primary constructor
     * has it on its field. A member that cannot be made accessible is skipped.
     */
    private Source annotatedSource(Class<?> type) {
        for (Class<?> owner : lookupOrder(type)) {
            for (Field field : owner.getDeclaredFields()) {
                Annotation annotation = idAnnotation(field);
                if (!Modifier.isStatic(field.getModifiers()) && annotation != null) {
                    Source source = fieldSource(field, label(annotation, field.getName()));
                    if (source != null) {
                        return source;
                    }
                }
            }
            for (Method method : owner.getDeclaredMethods()) {
                Annotation annotation = idAnnotation(method);
                if (!Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0 && !method.isSynthetic()
                        && annotation != null) {
                    Source source = methodSource(method, label(annotation, method.getName()));
                    if (source != null) {
                        return source;
                    }
                }
            }
        }
        return null;
    }

    private Source sourceFor(Class<?> owner, AugmentorConfig.ClassEntry entry) {
        String member;
        Source source = null;
        switch (entry.spec()) {
            case IdSpec.Annotations annotations -> {
                return annotatedSource(owner);
            }
            case IdSpec.Excluded excluded -> {
                return null;
            }
            case IdSpec.MethodSpec spec -> {
                member = "method " + spec.memberName() + "()";
                for (Class<?> type : methodLookupOrder(owner)) {
                    Method method = noArgumentMethod(type, spec.memberName());
                    if (method != null) {
                        source = methodSource(method, method.getName());
                        break;
                    }
                }
            }
            case IdSpec.FieldSpec spec -> {
                member = "field or property " + spec.memberName();
                for (Class<?> type : lookupOrder(owner)) {
                    Field field = instanceField(type, spec.memberName());
                    if (field != null) {
                        source = fieldSource(field, field.getName());
                        break;
                    }
                }
                // Without a field, e.g. in an interface or for a Kotlin property without a backing field: its getter.
                if (source == null) {
                    String getter = propertyGetter(spec.memberName());
                    for (Class<?> type : methodLookupOrder(owner)) {
                        Method method = noArgumentMethod(type, getter);
                        if (method != null) {
                            source = methodSource(method, spec.memberName());
                            break;
                        }
                    }
                }
            }
        }
        if (source == null) {
            if (!entry.isPattern()) {
                Log.warn(missingMember(entry, member));
            } else if (Log.isDebug()) {
                // A pattern is not expected to fit every class it matches.
                Log.debug(() -> missingMember(entry, member) + " in " + owner.getName());
            }
        }
        return source;
    }

    private static String missingMember(AugmentorConfig.ClassEntry entry, String member) {
        return "[augment.receiver] \"" + entry.key() + "\": no " + member + " found";
    }

    /** The JVM name of a Kotlin property's getter: {@code id} has {@code getId()}, {@code isActive} has {@code isActive()}. */
    static String propertyGetter(String property) {
        boolean isPrefixed = property.length() > 2 && property.startsWith("is") && !Character.isLowerCase(property.charAt(2));
        return isPrefixed ? property : "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
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

    /**
     * Where the members of an id source are looked up: the class and its superclasses, stopping at {@code Object}; for
     * an interface, the interface and the interfaces it extends, directly or not, nearest first.
     */
    private static List<Class<?>> lookupOrder(Class<?> type) {
        List<Class<?>> order = new ArrayList<>();
        if (type.isInterface()) {
            order.add(type);
            for (int i = 0; i < order.size(); i++) {
                for (Class<?> superInterface : order.get(i).getInterfaces()) {
                    if (!order.contains(superInterface)) {
                        order.add(superInterface);
                    }
                }
            }
        } else {
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                order.add(current);
            }
        }
        return order;
    }

    /**
     * Where a configured method or getter is looked up: {@link #lookupOrder}, and for a class then the interfaces it and
     * its superclasses implement, directly or not, nearest first, so that a default method a Java class inherits is
     * found. Kotlin compiles such a method into the class as well.
     */
    private static List<Class<?>> methodLookupOrder(Class<?> type) {
        List<Class<?>> order = lookupOrder(type);
        if (type.isInterface()) {
            return order;
        }
        List<Class<?>> classes = List.copyOf(order);
        for (Class<?> current : classes) {
            for (Class<?> implemented : current.getInterfaces()) {
                for (Class<?> iface : lookupOrder(implemented)) {
                    if (!order.contains(iface)) {
                        order.add(iface);
                    }
                }
            }
        }
        return order;
    }

    public static Annotation idAnnotation(AnnotatedElement element) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            if (annotation.annotationType().getName().equals(STACK_TRACE_ID)) {
                return annotation;
            }
        }
        return null;
    }

    /** Text on one line and with braces instead of parentheses: ids, and labels from an annotation's {@code name}. */
    public static String oneLineBraced(String text) {
        String singleLine = text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 ? LINE_BREAKS.matcher(text).replaceAll(" ") : text;
        return singleLine.replace('(', '{').replace(')', '}');
    }

    /** The annotation's {@code name} if set, cleaned by {@link #oneLineBraced}, otherwise {@code defaultLabel}. */
    public static String label(Annotation annotation, String defaultLabel) {
        if (annotation == null) {
            return defaultLabel;
        }
        try {
            if (annotation.annotationType().getMethod("name").invoke(annotation) instanceof String name && !name.isEmpty()) {
                return oneLineBraced(name);
            }
        } catch (ReflectiveOperationException e) {
            // no usable name: use the default
        }
        return defaultLabel;
    }
}
