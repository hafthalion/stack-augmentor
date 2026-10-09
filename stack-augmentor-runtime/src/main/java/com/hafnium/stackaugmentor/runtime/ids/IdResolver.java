package com.hafnium.stackaugmentor.runtime.ids;

import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.IdSpec;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    /** The hex digits of a hashed parameter id. */
    private static final int HASH_LENGTH = 8;

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

    private final ClassValue<List<Source>> sources = new ClassValue<>() {
        @Override
        protected List<Source> computeValue(Class<?> type) {
            List<Source> found = List.copyOf(findSources(type));
            if (Log.isDebug()) {
                Log.debug(() -> "id sources of " + type.getName() + ": " + (found.isEmpty() ? "none"
                        : found.stream().map(Source::description).collect(Collectors.joining("; "))));
            }
            return found;
        }
    };

    public IdResolver(AugmentorConfig config) {
        this.config = config;
    }

    /** Whether frames of this throwable get ids: {@code [augment] exceptions}, see {@link AugmentorConfig#augments}. */
    public boolean augments(Throwable thrown) {
        return config.augments(thrown.getClass());
    }

    /** {@link #receiverIds(Object, String)} for a method that the object's own class declares. */
    public List<NamedId> receiverIds(Object target) {
        return receiverIds(target, target.getClass().getName());
    }

    /**
     * The ids of the object a frame runs on, one per id source, in the configured order; empty if there is no id
     * source. The id sources are those of the
     * class declaring the frame's method, not of the object's runtime class: a subclass, e.g. a proxy or a mock, shows
     * the ids of the class whose method it runs, and a default method shows the ids of its interface's entry. Their
     * members are read from the object.
     *
     * @param declaringClass the name of the class that declares the method
     */
    public List<NamedId> receiverIds(Object target, String declaringClass) {
        List<Source> found = sources.get(declaringClass(target.getClass(), declaringClass));
        if (found.isEmpty()) {
            return List.of();
        }
        List<NamedId> ids = new ArrayList<>(found.size());
        for (Source source : found) {
            ids.add(new NamedId(source.name(), read(source, target)));
        }
        return ids;
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

    /**
     * The id of an argument selected with {@code #}: {@code #} and the first 8 hex digits of the SHA-256 of its text,
     * e.g. {@code #5d41402a}. The same value gives the same hash, so it can be followed across log lines and
     * incidents without being shown. {@code null} stays {@code null}.
     */
    public String hashedParamId(Object value) {
        return guarded(() -> value == null ? "null" : hash(text(value)));
    }

    static String hash(String text) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform has SHA-256.
            throw new IllegalStateException(e);
        }
        StringBuilder hex = new StringBuilder(HASH_LENGTH + 1).append('#');
        for (int i = 0; i < HASH_LENGTH / 2; i++) {
            hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16)).append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return hex.toString();
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
     * of superclasses do not apply. Its fields and methods, or with {@code "@"} its {@code @StackTraceId} members, are
     * looked up in the class and its superclasses, or in an interface and the interfaces it extends. Without a deciding
     * entry, or with {@code "-"}, the class has no id source, even if it is annotated.
     */
    private List<Source> findSources(Class<?> type) {
        AugmentorConfig.ClassEntry entry = config.classEntry(type.getName());
        if (entry == null) {
            return List.of();
        }
        return switch (entry.spec()) {
            case IdSpec.Annotations annotations -> annotatedSources(type);
            case IdSpec.Excluded excluded -> List.of();
            case IdSpec.MemberSpec spec -> sources(type, entry, List.of(spec));
            case IdSpec.MemberList list -> sources(type, entry, list.members());
        };
    }

    /** The configured members that are found; a missing one is reported and left out. */
    private List<Source> sources(Class<?> owner, AugmentorConfig.ClassEntry entry, List<IdSpec.MemberSpec> members) {
        List<Source> found = new ArrayList<>(members.size());
        for (IdSpec.MemberSpec member : members) {
            Source source = sourceFor(owner, entry, member);
            if (source != null) {
                found.add(source);
            }
        }
        return found;
    }

    /**
     * Every {@code @StackTraceId} on a field or a no-argument method, one id each: per class, nearest first, its fields
     * in declaration order, then its methods by name. A Kotlin property annotated in the primary constructor has it on
     * its field. A member that cannot be made accessible is skipped, and so is one whose label a nearer one already
     * has, e.g. a field hidden by a subclass's field of the same name.
     */
    private List<Source> annotatedSources(Class<?> type) {
        List<Source> found = new ArrayList<>();
        for (Class<?> owner : lookupOrder(type)) {
            for (Field field : owner.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && idAnnotation(field) != null) {
                    addUnlabelled(found, fieldSource(field, field.getName()));
                }
            }
            Method[] methods = owner.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method method : methods) {
                if (idAnnotation(method) != null
                        && isUsableIdMethod(Modifier.isStatic(method.getModifiers()), method.getParameterCount(), method.isSynthetic())) {
                    addUnlabelled(found, methodSource(method, method.getName()));
                }
            }
        }
        return found;
    }

    private static void addUnlabelled(List<Source> found, Source source) {
        if (source != null && found.stream().noneMatch(it -> it.name().equals(source.name()))) {
            found.add(source);
        }
    }

    private Source sourceFor(Class<?> owner, AugmentorConfig.ClassEntry entry, IdSpec.MemberSpec memberSpec) {
        String member;
        Lookup lookup = new Lookup();
        switch (memberSpec) {
            case IdSpec.MethodSpec spec -> {
                member = "method " + spec.memberName() + "()";
                for (Class<?> type : methodLookupOrder(owner)) {
                    if (lookup.method(noArgumentMethod(type, spec.memberName()), spec.memberName())) {
                        break;
                    }
                }
            }
            case IdSpec.FieldSpec spec -> {
                member = "field or property " + spec.memberName();
                for (Class<?> type : lookupOrder(owner)) {
                    if (lookup.field(instanceField(type, spec.memberName()), spec.memberName())) {
                        break;
                    }
                }
                // Without a usable field, e.g. in an interface, for a Kotlin property without a backing field, or for a
                // field that cannot be made accessible: its getter.
                if (lookup.source == null) {
                    String getter = propertyGetter(spec.memberName());
                    for (Class<?> type : methodLookupOrder(owner)) {
                        if (lookup.method(noArgumentMethod(type, getter), spec.memberName())) {
                            break;
                        }
                    }
                }
            }
        }
        if (lookup.source == null) {
            if (lookup.inaccessible != null) {
                // The configured member exists, so this is a problem whether the entry is a pattern or not.
                Log.warn("[augment.receiver] \"" + entry.key() + "\": cannot access " + lookup.inaccessible);
            } else if (!entry.isPattern()) {
                Log.warn(missingMember(entry, member));
            } else if (Log.isDebug()) {
                // A pattern is not expected to fit every class it matches.
                Log.debug(() -> missingMember(entry, member) + " in " + owner.getName());
            }
        }
        return lookup.source;
    }

    /**
     * The search for a configured member: the first accessible one found becomes the source. The first one found that
     * cannot be made accessible is remembered, so that a lookup that finds nothing else reports it instead of a missing
     * member.
     */
    private static final class Lookup {
        Source source;
        Member inaccessible;

        /** Whether the search is over: {@code field} exists and is accessible. */
        boolean field(Field field, String name) {
            if (field == null) {
                return false;
            }
            if (field.trySetAccessible()) {
                source = new FieldSource(field, name);
                return true;
            }
            remember(field);
            return false;
        }

        /** Whether the search is over: {@code method} exists and is accessible. */
        boolean method(Method method, String name) {
            if (method == null) {
                return false;
            }
            if (method.trySetAccessible()) {
                source = new MethodSource(method, name);
                return true;
            }
            remember(method);
            return false;
        }

        private void remember(Member member) {
            if (inaccessible == null) {
                inaccessible = member;
            }
        }
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

    /**
     * Whether an {@code @StackTraceId} method can give ids: an instance method without parameters that the compiler
     * did not generate. The build-time and agent type matching use the same rule, so that a class whose only annotated
     * method takes arguments is not instrumented.
     */
    public static boolean isUsableIdMethod(boolean isStatic, int parameterCount, boolean isSynthetic) {
        return !isStatic && parameterCount == 0 && !isSynthetic;
    }

    public static Annotation idAnnotation(AnnotatedElement element) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            if (annotation.annotationType().getName().equals(STACK_TRACE_ID)) {
                return annotation;
            }
        }
        return null;
    }

    /** Text on one line and with braces instead of parentheses, as ids are shown. */
    private static String oneLineBraced(String text) {
        String singleLine = text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 ? LINE_BREAKS.matcher(text).replaceAll(" ") : text;
        return singleLine.replace('(', '{').replace(')', '}');
    }
}
