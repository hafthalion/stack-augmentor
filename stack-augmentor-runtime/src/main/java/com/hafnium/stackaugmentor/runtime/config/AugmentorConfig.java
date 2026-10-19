package com.hafnium.stackaugmentor.runtime.config;

import com.hafnium.stackaugmentor.runtime.values.FrameFormat;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The configuration, read from a TOML file:
 *
 * <pre>{@code
 * debug = false
 * inPlaceModification = false  # write frames into the exception's own stack trace instead of copying it; needs
 *                              # java.lang open (the agent opens it), otherwise startup fails
 *
 * [augment]                   # how frames look
 * frameFormat = "$class$receiver.$method$params"
 * receiverFormat = "{$name=$value, ...}"
 * paramsFormat = "{$name=$value, ...}"
 * maxValueLength = 64
 *
 * [augment.exceptions]       # which throwables are augmented: true or false, the first matching entry wins; a
 *                            # class name matches that class, with "+" its subclasses too, a pattern the runtime class
 *                            # only; a throwable that no entry matches is not, so without the table none is: "**" =
 *                            # true for all
 * "com.acme.ControlFlowException+" = false
 * "com.acme.**" = true
 * "java.io.IOException+" = true   # and its subclasses
 *
 * [augment.annotations]      # where the annotations are read: true or false, the first matching entry wins, keys as
 *                            # in [augment.exceptions]; a class that no entry matches is not, so without the table no
 *                            # annotations are read
 * "com.acme.generated.**" = false
 * "com.acme.**" = true
 *
 * # Keys of [augment.receiver] and [augment.params]: "<class>::<methods>". A class name matches that class only, with
 * # "+" after it also its subclasses and implementing classes; a pattern matches class names only. The methods are
 * # names or patterns separated by commas, "*" for all. For each method the first matching entry of a table wins; a
 * # method that no entry matches uses the annotations, if [augment.annotations] selects its class.
 *
 * [augment.receiver]         # receiver members: a field, a "method()", several separated by commas, "-" for none
 * "com.thirdparty.Order+::*" = "getOrderNumber()"   # Order and its subclasses
 * "com.thirdparty.OrderLine::*" = "tenant, lineId()"
 * "com.thirdparty.Invoice::pay, cancel*" = "number"   # frames of pay and cancel... show the number,
 * "com.thirdparty.Invoice::print" = "copy"             # frames of print the copy, the others no receiver members
 *
 * [augment.params]           # parameter values: names and indexes separated by commas ("#" after one hashes it), "-"
 *                            # for none
 * "com.thirdparty.Shipment::<init>" = "orderId"   # constructors, also matched by wildcards such as "::*"
 * "com.thirdparty.OrderService+::process" = "order, 2, email#, 3#"
 * "com.thirdparty.**.AuditRepository::*" = "-"
 * "com.thirdparty.**.*Repository::find*" = "0"
 * }</pre>
 *
 * <p>The tables are independent: {@code [augment.receiver]} decides the receiver members, {@code [augment.params]} the
 * parameter values, by names and patterns; {@code [augment.annotations]} decides where the annotations count for the
 * methods that these tables do not match. Which methods get instrumented follows from all three. Keys may use
 * wildcards: {@code *} within one package segment (or name), {@code **} across segments, {@code ?} one character.
 * Where entries of a table overlap, the first in the file decides, see {@link #classEntry(Class, String)} and
 * {@link #paramRefs}. Immutable. Equality covers the configured values only.
 */
public final class AugmentorConfig {

    /** The method name of constructors, in {@code [augment.params]} keys as in stack traces: {@code "com.acme.Order::<init>"}. */
    public static final String CONSTRUCTOR = "<init>";

    /** After a class name in a key, {@code "com.acme.Order+::*"}: the entry matches the subclasses too. */
    public static final String SUBCLASSES = "+";

    /**
     * Separates the class from the methods in {@code [augment.params]} and {@code [augment.receiver]} keys
     * ({@code "com.acme.Order::ship, cancel"}).
     */
    public static final String METHOD_SEPARATOR = "::";

    public static final String DEFAULT_FRAME_FORMAT = "$class$receiver.$method$params";
    public static final String DEFAULT_RECEIVER_FORMAT = "{$name=$value, ...}";
    public static final String DEFAULT_PARAMS_FORMAT = "{$name=$value, ...}";
    public static final int DEFAULT_MAX_VALUE_LENGTH = 64;
    public static final String CONFIG_PROPERTY = "stackaugmentor.config";
    /** Overrides {@code inPlaceModification} of the file: {@code -Dstackaugmentor.inPlaceModification=true}. */
    public static final String IN_PLACE_MODIFICATION_PROPERTY = "stackaugmentor.inPlaceModification";

    /**
     * The key that the instrumentation passes for a method whose receiver members come from the annotations, as no
     * {@code [augment.receiver]} entry matches it: see {@link #receiverEntry(String)}. The instrumentation decides
     * this from its own {@code [augment.annotations]} and {@code @Augmented}; the runtime does not check them again.
     */
    public static final String ANNOTATIONS = "@";

    /** The value that stands for "ignore", in both tables. */
    public static final String EXCLUDED = "-";

    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");
    private static final Pattern INDEX = Pattern.compile("[0-9]{1,3}");

    private final Map<String, ReceiverSpec> classes;
    private final Map<String, List<ParamRef>> methods;
    private final String frameFormat;
    private final String receiverFormat;
    private final String paramsFormat;
    private final int maxValueLength;
    private final boolean debug;
    private final boolean inPlaceModification;
    private final Map<String, Boolean> exceptions;
    private final Map<String, Boolean> annotations;

    /**
     * The class part of a key, the same in all three tables: a class name, which matches that class only; with
     * {@code +} after it ({@code "com.acme.Order+"}) also its subclasses and, for an interface, the classes
     * implementing it, as {@code instanceof} does; or a class pattern, which matches class names only.
     *
     * @param pattern {@code null} for a class name
     */
    private record ClassPart(String className, boolean subclasses, Pattern pattern) {

        static ClassPart of(String classPart) {
            boolean subclasses = classPart.endsWith(SUBCLASSES);
            String className = subclasses ? classPart.substring(0, classPart.length() - SUBCLASSES.length()) : classPart;
            return new ClassPart(className, subclasses, isPattern(className) ? globToRegex(className) : null);
        }

        /** @param isA see {@link EntryKey#matchesClass} */
        boolean matches(String name, Predicate<String> isA) {
            if (pattern != null) {
                return pattern.matcher(name).matches();
            }
            return subclasses ? isA.test(className) : className.equals(name);
        }
    }

    /** An {@code [augment.exceptions]} or {@code [augment.annotations]} entry. */
    private record SwitchEntry(ClassPart classPart, boolean on) {
    }

    /** The value of the first entry that matches the class, {@code false} if none does. */
    private static boolean firstMatch(List<SwitchEntry> entries, String className, Predicate<String> isA) {
        for (SwitchEntry entry : entries) {
            if (entry.classPart().matches(className, isA)) {
                return entry.on();
            }
        }
        return false;
    }

    private static List<SwitchEntry> switchEntries(Map<String, Boolean> table) {
        return table.entrySet().stream().map(entry -> new SwitchEntry(ClassPart.of(entry.getKey()), entry.getValue())).toList();
    }

    /** The {@code [augment.exceptions]} entries, in the order of the file. */
    private final List<SwitchEntry> exceptionEntries;

    /** Per throwable class, whether it is augmented: the value of the first {@code [augment.exceptions]} entry it matches. */
    private final ClassValue<Boolean> augmentedExceptions = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return firstMatch(exceptionEntries, type.getName(), name -> isA(type, name));
        }
    };

    /** The {@code [augment.annotations]} entries, in the order of the file. */
    private final List<SwitchEntry> annotationEntries;

    /** Per class, whether its annotations are read: the value of the first {@code [augment.annotations]} entry it matches. */
    private final ClassValue<Boolean> annotatedClasses = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return firstMatch(annotationEntries, type.getName(), name -> isA(type, name));
        }
    };

    /**
     * The key of an {@code [augment.receiver]} or {@code [augment.params]} entry, {@code "<class>::<methods>"}: a
     * class name, which matches that class only, or with {@code +} after it ({@code "com.acme.Order+"}) also its
     * subclasses and, for an interface, the classes implementing it, as {@code instanceof} does; or a class pattern,
     * which matches class names only. After {@code ::} the method names or patterns, separated by commas. The class is
     * the one that declares a frame's method.
     */
    public static final class EntryKey {

        private final String key;
        private final ClassPart classPart;
        private final List<String> methods;
        private final List<Pattern> methodPatterns;

        /** @param key a key as {@link #key()} gives it, e.g. {@code "com.acme.Order+::ship, cancel*"} */
        public EntryKey(String key) {
            this.key = Objects.requireNonNull(key, "key");
            int separator = key.indexOf(METHOD_SEPARATOR);
            if (separator < 0) {
                throw new IllegalArgumentException("no " + METHOD_SEPARATOR + " in " + key);
            }
            this.classPart = ClassPart.of(key.substring(0, separator));
            String methodPart = key.substring(separator + METHOD_SEPARATOR.length());
            this.methods = List.of(methodPart.split(",", -1)).stream().map(String::trim).toList();
            this.methodPatterns = methods.stream().map(AugmentorConfig::globToRegex).toList();
        }

        public String key() {
            return key;
        }

        /** The class name or pattern, without {@code +}. */
        public String className() {
            return classPart.className();
        }

        /** Whether the key has {@code +} after the class name, so that it matches the subclasses too. */
        public boolean subclasses() {
            return classPart.subclasses();
        }

        /** Whether the class part has wildcards. */
        public boolean isPattern() {
            return classPart.pattern() != null;
        }

        /** The method names and patterns after {@code ::}, in the order of the file. */
        public List<String> methods() {
            return methods;
        }

        /**
         * Whether the key matches the class: its name, or with {@code +} also as a superclass or an implemented
         * interface, or for a pattern the name of the class.
         *
         * @param isA whether the class has the given name, or one of its superclasses or the interfaces it implements,
         *            directly or not, has it; see {@link #isA(Class, String)}
         */
        public boolean matchesClass(String name, Predicate<String> isA) {
            return classPart.matches(name, isA);
        }

        /** Whether the key applies to frames of this method: one of the names or patterns matches it. */
        public boolean appliesTo(String methodName) {
            for (Pattern pattern : methodPatterns) {
                if (pattern.matcher(methodName).matches()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return key;
        }
    }

    /**
     * Where the receiver members of a method's frames come from: an {@code [augment.receiver]} entry, or without one the
     * {@code @AugmentedReceiver} members ({@link #ANNOTATION_ENTRY}).
     */
    public sealed interface ReceiverEntry permits ClassEntry, AnnotationEntry {

        /** The key that the instrumentation passes to the runtime: the entry's key, or {@link #ANNOTATIONS}. */
        String key();
    }

    /** An {@code [augment.receiver]} entry: its key and the receiver members it names. */
    public static final class ClassEntry implements ReceiverEntry {

        private final String text;
        private final EntryKey key;
        private final ReceiverSpec spec;

        public ClassEntry(String key, ReceiverSpec spec) {
            this.text = key;
            this.key = new EntryKey(key);
            this.spec = Objects.requireNonNull(spec, "spec");
        }

        @Override
        public String key() {
            return text;
        }

        /** The class name or pattern of the key, without {@code +}. */
        public String className() {
            return key.className();
        }

        public ReceiverSpec spec() {
            return spec;
        }

        /** The method names and patterns after {@code ::}, in the order of the file. */
        public List<String> methods() {
            return key.methods();
        }

        /** Whether the class part of the key has wildcards. */
        public boolean isPattern() {
            return key.isPattern();
        }

        /** See {@link EntryKey#matchesClass}. */
        public boolean matchesClass(String name, Predicate<String> isA) {
            return key.matchesClass(name, isA);
        }

        /** See {@link EntryKey#appliesTo}. */
        public boolean appliesTo(String methodName) {
            return key.appliesTo(methodName);
        }

        @Override
        public String toString() {
            return key() + "=" + specText(spec);
        }
    }

    /**
     * The receiver members of a method that no {@code [augment.receiver]} entry matches, where {@code [augment.annotations]}
     * selects the class and {@code @Augmented} the method: its {@code @AugmentedReceiver} members.
     */
    public record AnnotationEntry() implements ReceiverEntry {

        @Override
        public String key() {
            return ANNOTATIONS;
        }

        @Override
        public String toString() {
            return ANNOTATIONS + "=@AugmentedReceiver";
        }
    }

    /** The only {@link AnnotationEntry}. */
    public static final AnnotationEntry ANNOTATION_ENTRY = new AnnotationEntry();

    /** The {@code [augment.receiver]} entries, in the order of the file. */
    private final List<ClassEntry> classEntryList;

    /** The {@code [augment.receiver]} entries by key. */
    private final Map<String, ClassEntry> classEntriesByKey;

    /** Per class, the {@code [augment.receiver]} entries whose class part matches it, in the order of the file. */
    private final ClassValue<List<ClassEntry>> classEntries = new ClassValue<>() {
        @Override
        protected List<ClassEntry> computeValue(Class<?> type) {
            return classEntries(type.getName(), name -> isA(type, name));
        }
    };

    /** An {@code [augment.params]} entry: its key and the parameters it selects. */
    private record ParamsEntry(EntryKey key, List<ParamRef> refs) {
    }

    /** The {@code [augment.params]} entries, in the order of the file. */
    private final List<ParamsEntry> paramsEntries;

    /**
     * The classpath resource, next to this class, with the configuration that applies when none is given: all exceptions
     * are augmented, with the annotated receiver members and parameter values of the classes with {@code @Augmented}.
     */
    public static final String BUILT_IN_RESOURCE = "stack-augmentor-defaults.toml";

    /** Where the {@link #builtIn()} configuration comes from, for the debug output. */
    public static final String BUILT_IN_LOCATION = "none, using the built-in configuration ("
            + AugmentorConfig.class.getPackageName().replace('.', '/') + "/" + BUILT_IN_RESOURCE + ")";

    /** The default values: no class, method, annotation or exception entries, so nothing is augmented. See {@link #builtIn()}. */
    public AugmentorConfig() {
        this(Map.of(), Map.of(), DEFAULT_FRAME_FORMAT, DEFAULT_RECEIVER_FORMAT, DEFAULT_PARAMS_FORMAT,
                DEFAULT_MAX_VALUE_LENGTH, false, false, Map.of(), Map.of());
    }

    /**
     * @param classes   receiver members by {@code "<class>::<methods>"} key ({@link EntryKey}): the
     *                  {@code [augment.receiver]} table
     * @param methods   selected parameters by {@code "<class>::<methods>"} key ({@link EntryKey}): the {@code [augment.params]}
     *                  table
     * @param exceptions whether throwables are augmented, by class name (with {@code +} also the subclasses) or class pattern
     *                  (of the runtime class), in the order the first matching entry is looked for: the {@code [augment.exceptions]}
     *                  table; empty for none, {@code "**" = true} for all
     * @param annotations whether the annotations of a class are read, by class name (with {@code +} also the subclasses) or
     *                  class pattern, in the order the first matching entry is looked for: the {@code [augment.annotations]}
     *                  table; empty for none, {@code "**" = true} for all
     */
    public AugmentorConfig(Map<String, ReceiverSpec> classes, Map<String, List<ParamRef>> methods,
                           String frameFormat, String receiverFormat, String paramsFormat, int maxValueLength,
                           boolean debug, boolean inPlaceModification, Map<String, Boolean> exceptions,
                           Map<String, Boolean> annotations) {
        this.classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        Map<String, List<ParamRef>> methodsCopy = new LinkedHashMap<>();
        methods.forEach((target, refs) -> methodsCopy.put(target, List.copyOf(refs)));
        this.methods = Collections.unmodifiableMap(methodsCopy);
        this.frameFormat = Objects.requireNonNull(frameFormat, "frameFormat");
        this.receiverFormat = Objects.requireNonNull(receiverFormat, "receiverFormat");
        this.paramsFormat = Objects.requireNonNull(paramsFormat, "paramsFormat");
        this.maxValueLength = maxValueLength;
        this.debug = debug;
        this.inPlaceModification = inPlaceModification;
        this.exceptions = Collections.unmodifiableMap(new LinkedHashMap<>(exceptions));
        this.exceptionEntries = switchEntries(this.exceptions);
        this.annotations = Collections.unmodifiableMap(new LinkedHashMap<>(annotations));
        this.annotationEntries = switchEntries(this.annotations);
        Map<String, ClassEntry> classEntries = new LinkedHashMap<>();
        this.classes.forEach((key, spec) -> classEntries.put(key, new ClassEntry(key, spec)));
        this.classEntriesByKey = Collections.unmodifiableMap(classEntries);
        this.classEntryList = List.copyOf(classEntries.values());
        List<ParamsEntry> paramsEntries = new ArrayList<>();
        this.methods.forEach((target, refs) -> paramsEntries.add(new ParamsEntry(new EntryKey(target), refs)));
        this.paramsEntries = List.copyOf(paramsEntries);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Receiver members by {@code "<class>::<methods>"} key: the {@code [augment.receiver]} table. */
    public Map<String, ReceiverSpec> classes() {
        return classes;
    }

    /** Selected parameters by {@code "<class>::<methods>"} key: the {@code [augment.params]} table. */
    public Map<String, List<ParamRef>> methods() {
        return methods;
    }

    public String frameFormat() {
        return frameFormat;
    }

    public String receiverFormat() {
        return receiverFormat;
    }

    public String paramsFormat() {
        return paramsFormat;
    }

    public int maxValueLength() {
        return maxValueLength;
    }

    public boolean debug() {
        return debug;
    }

    /** The {@code [augment.exceptions]} entries, in the order of the file; empty when no throwable is augmented. */
    public Map<String, Boolean> exceptions() {
        return exceptions;
    }

    /** The {@code [augment.annotations]} entries, in the order of the file; empty when no annotations are read. */
    public Map<String, Boolean> annotations() {
        return annotations;
    }

    /**
     * Whether the annotations of the class that declares a frame's method are read, for the methods that no
     * {@code [augment.receiver]} or {@code [augment.params]} entry matches: the value of the first
     * {@code [augment.annotations]} entry that matches, and {@code false} if none does. Entries match as in
     * {@code [augment.exceptions]}.
     */
    public boolean readsAnnotations(Class<?> type) {
        return annotatedClasses.get(type);
    }

    /** {@link #readsAnnotations(Class)} for a class known by its name; {@code isA} as in {@link EntryKey#matchesClass}. */
    public boolean readsAnnotations(String className, Predicate<String> isA) {
        return firstMatch(annotationEntries, className, isA);
    }

    /** The {@code [augment.annotations]} entries for the debug log, e.g. {@code com.acme.generated.**=false, com.acme.**=true}. */
    public String annotationsDescription() {
        String text = annotations.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        return text.isEmpty() ? "none" : text;
    }

    /**
     * Whether throwables of this runtime class are augmented: the value of the first {@code [augment.exceptions]}
     * entry that matches decides, and a class that no entry matches is not, so without entries none is. A class
     * name matches that class only, with {@code +} also its subclasses, as {@code instanceof} does; a pattern matches
     * the name of the runtime class only, as in {@code [augment.receiver]}.
     */
    public boolean augments(Class<? extends Throwable> type) {
        return augmentedExceptions.get(type);
    }

    /** The {@code [augment.exceptions]} entries for the debug log, e.g. {@code com.acme.Flow=false, com.acme.**=true}. */
    public String exceptionsDescription() {
        String text = exceptions.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        return text.isEmpty() ? "none" : text;
    }

    /**
     * Whether the handler writes frames into the exception's own stack trace instead of copying it: the
     * {@code inPlaceModification} key, unless the system property {@value #IN_PLACE_MODIFICATION_PROPERTY} is set.
     */
    public boolean inPlaceModification() {
        String property = System.getProperty(IN_PLACE_MODIFICATION_PROPERTY);
        return property != null ? Boolean.parseBoolean(property.trim()) : inPlaceModification;
    }

    /** The {@code [augment.receiver]} entries for the debug log, e.g. {@code com.acme.**::*=@, com.acme.Order::*=getId()}. */
    public String classesDescription() {
        String text = classes.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + specText(entry.getValue()))
                .collect(Collectors.joining(", "));
        return text.isEmpty() ? "none" : text;
    }

    /** An {@code [augment.receiver]} value for the debug log, e.g. {@code getId()}, or {@code [tenant, id()]} for several. */
    public static String specText(ReceiverSpec spec) {
        return switch (spec) {
            case ReceiverSpec.MethodSpec method -> method.memberName() + "()";
            case ReceiverSpec.FieldSpec field -> field.memberName();
            case ReceiverSpec.MemberList list -> list.members().stream().map(AugmentorConfig::specText)
                    .collect(Collectors.joining(", ", "[", "]"));
            case ReceiverSpec.Excluded excluded -> EXCLUDED;
        };
    }

    /** The {@code [augment.params]} entries for the debug log, e.g. {@code com.acme.Order::process[order, 2, email#]}. */
    public String methodsDescription() {
        String text = methods.entrySet().stream()
                .map(entry -> entry.getKey() + entry.getValue().stream()
                        .map(ref -> switch (ref) {
                            case ParamRef.ByName byName -> byName.name() + (byName.hashed() ? "#" : "");
                            case ParamRef.ByIndex byIndex -> byIndex.index() + (byIndex.hashed() ? "#" : "");
                            case ParamRef.Excluded excluded -> EXCLUDED;
                        })
                        .collect(Collectors.joining(", ", "[", "]")))
                .collect(Collectors.joining(", "));
        return text.isEmpty() ? "none" : text;
    }

    /**
     * Whether anything can be augmented: without class or method entries other than {@code "-"}, and without an
     * {@code [augment.annotations]} entry that is {@code true}, nothing is.
     */
    public boolean hasAugmentEntries() {
        return classes.values().stream().anyMatch(spec -> !(spec instanceof ReceiverSpec.Excluded))
                || methods.values().stream().anyMatch(refs -> !refs.contains(new ParamRef.Excluded()))
                || annotations.containsValue(true);
    }

    /**
     * The {@code [augment.receiver]} entries whose class part matches the class, in the order of the file: see
     * {@link EntryKey#matchesClass}. The class is the one that declares a frame's method.
     */
    public List<ClassEntry> classEntries(Class<?> type) {
        return classEntries.get(type);
    }

    /**
     * The deciding {@code [augment.receiver]} entry of a frame, or {@code null}: the first entry in the order of the file
     * whose class part matches the class that declares the frame's method (see {@link #classEntries(Class)}) and whose
     * methods include the method.
     */
    public ClassEntry classEntry(Class<?> type, String methodName) {
        return firstApplying(classEntries(type), methodName);
    }

    /**
     * {@link #classEntries(Class)} for a class known by its name, e.g. one that is being instrumented.
     *
     * @param isA whether the class has the given name, or one of its superclasses or the interfaces it implements,
     *            directly or not, has it; see {@link #isA(Class, String)}
     */
    public List<ClassEntry> classEntries(String className, Predicate<String> isA) {
        List<ClassEntry> matching = new ArrayList<>();
        for (ClassEntry entry : classEntryList) {
            if (entry.matchesClass(className, isA)) {
                matching.add(entry);
            }
        }
        return List.copyOf(matching);
    }

    /** The first of these entries that applies to the method, or {@code null}. */
    public static ClassEntry firstApplying(List<ClassEntry> entries, String methodName) {
        for (ClassEntry entry : entries) {
            if (entry.appliesTo(methodName)) {
                return entry;
            }
        }
        return null;
    }

    /** The {@code [augment.receiver]} entry with this key, as {@link ClassEntry#key()} gives it, or {@code null}. */
    public ClassEntry classEntry(String key) {
        return classEntriesByKey.get(key);
    }

    /**
     * The receiver entry that the instrumentation found for a method, by its key: the {@code [augment.receiver]} entry
     * with this key, or for {@link #ANNOTATIONS} the {@link #ANNOTATION_ENTRY}, whatever this configuration's
     * {@code [augment.annotations]} says (the instrumentation decided that, as it did for the parameters); {@code null}
     * otherwise.
     */
    public ReceiverEntry receiverEntry(String key) {
        return ANNOTATIONS.equals(key) ? ANNOTATION_ENTRY : classEntry(key);
    }

    /**
     * Whether the class, a superclass or an implemented interface has this name, as {@code instanceof} would say.
     * Compares names, so it loads no class.
     */
    public static boolean isA(Class<?> type, String name) {
        if (type == null) {
            return false;
        }
        if (type.getName().equals(name) || isA(type.getSuperclass(), name)) {
            return true;
        }
        for (Class<?> each : type.getInterfaces()) {
            if (isA(each, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The parameters selected for a method by the first {@code [augment.params]} entry in the order of the file that
     * matches it: its class part matches the class that declares the method (see {@link EntryKey#matchesClass}), and
     * its methods include the method. Later entries play no part, so a {@code "-"} entry selects nothing even where a
     * later entry would select parameters. {@code [augment.receiver]} plays no part either.
     *
     * @param isA see {@link EntryKey#matchesClass}
     */
    public List<ParamRef> paramRefs(String className, Predicate<String> isA, String methodName) {
        List<ParamRef> refs = paramsEntry(className, isA, methodName);
        return refs == null || refs.contains(new ParamRef.Excluded()) ? List.of() : refs;
    }

    /**
     * The values of the first {@code [augment.params]} entry that matches the method, {@code "-"} included, or
     * {@code null} if none does, so that the method's parameter annotations may be used.
     */
    public List<ParamRef> paramsEntry(String className, Predicate<String> isA, String methodName) {
        for (ParamsEntry entry : paramsEntries) {
            if (entry.key().matchesClass(className, isA) && entry.key().appliesTo(methodName)) {
                return entry.refs();
            }
        }
        return null;
    }

    /**
     * Whether an {@code [augment.params]} entry other than {@code "-"} matches this class for some method, so that
     * {@link #paramRefs} may select parameters of its methods.
     */
    public boolean hasParamEntries(String className, Predicate<String> isA) {
        for (ParamsEntry entry : paramsEntries) {
            if (!entry.refs().contains(new ParamRef.Excluded()) && entry.key().matchesClass(className, isA)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a key of {@code [augment.receiver]} or {@code [augment.params]} has wildcards ({@code *} or {@code ?}). */
    public static boolean isPattern(String key) {
        return key.indexOf('*') >= 0 || key.indexOf('?') >= 0;
    }

    /**
     * Loads the TOML file named by the agent arguments ({@code config=<path>} or just {@code <path>}),
     * or by the {@code stackaugmentor.config} system property. Without either, the {@link #builtIn()} configuration
     * applies.
     */
    public static AugmentorConfig load(String agentArgs) {
        String location = location(agentArgs);
        return location != null ? load(Path.of(location)) : builtIn();
    }

    /** The configuration of the {@link #BUILT_IN_RESOURCE}, for when none is given. */
    public static AugmentorConfig builtIn() {
        try (InputStream in = AugmentorConfig.class.getResourceAsStream(BUILT_IN_RESOURCE)) {
            if (in == null) {
                throw new ConfigException("The built-in configuration " + BUILT_IN_RESOURCE + " is missing from the classpath");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), BUILT_IN_RESOURCE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads a TOML configuration file. */
    public static AugmentorConfig load(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new ConfigException("Configuration file not found: " + path);
        }
        String fileName = path.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".toml")) {
            throw new ConfigException(path + ": the configuration must be a TOML file ending in .toml");
        }
        try {
            return parse(Files.readString(path), fileName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The configuration file named by the agent arguments or the system property, if any. */
    public static String location(String agentArgs) {
        if (agentArgs != null && !agentArgs.trim().isEmpty()) {
            String trimmed = agentArgs.trim();
            return trimmed.startsWith("config=") ? trimmed.substring("config=".length()) : trimmed;
        }
        String property = System.getProperty(CONFIG_PROPERTY);
        return property != null && !property.isBlank() ? property : null;
    }

    /** Parses a TOML configuration. */
    public static AugmentorConfig parse(String text) {
        return parse(text, "configuration");
    }

    /** Parses a TOML configuration. {@code source} names it in error messages. */
    public static AugmentorConfig parse(String text, String source) {
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            String errors = toml.errors().stream().map(Object::toString).collect(Collectors.joining("; "));
            throw new ConfigException("Invalid TOML in " + source + ": " + errors);
        }
        return new ConfigReader(toml, source).read();
    }

    /** {@code *} matches within one package segment, {@code **} across segments, {@code ?} one character. */
    static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^.]*");
            } else if (c == '?') {
                regex.append("[^.]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
            i++;
        }
        return Pattern.compile(regex.toString());
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof AugmentorConfig that
                && classes.equals(that.classes)
                && methods.equals(that.methods)
                && frameFormat.equals(that.frameFormat)
                && receiverFormat.equals(that.receiverFormat)
                && paramsFormat.equals(that.paramsFormat)
                && maxValueLength == that.maxValueLength
                && debug == that.debug
                && inPlaceModification == that.inPlaceModification
                && exceptions.equals(that.exceptions)
                && annotations.equals(that.annotations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classes, methods, frameFormat, receiverFormat, paramsFormat, maxValueLength, debug, inPlaceModification,
                exceptions, annotations);
    }

    @Override
    public String toString() {
        return "AugmentorConfig[classes=" + classes + ", methods=" + methods
                + ", frameFormat=" + frameFormat + ", receiverFormat=" + receiverFormat + ", paramsFormat=" + paramsFormat
                + ", maxValueLength=" + maxValueLength + ", debug=" + debug + ", inPlaceModification=" + inPlaceModification + ", exceptions=" + exceptions + ", annotations=" + annotations + "]";
    }

    /** Starts from the defaults; every setter replaces one value. */
    public static final class Builder {

        private Map<String, ReceiverSpec> classes = Map.of();
        private Map<String, List<ParamRef>> methods = Map.of();
        private String frameFormat = DEFAULT_FRAME_FORMAT;
        private String receiverFormat = DEFAULT_RECEIVER_FORMAT;
        private String paramsFormat = DEFAULT_PARAMS_FORMAT;
        private int maxValueLength = DEFAULT_MAX_VALUE_LENGTH;
        private boolean debug;
        private boolean inPlaceModification;
        private Map<String, Boolean> exceptions = Map.of();
        private Map<String, Boolean> annotations = Map.of();

        private Builder() {
        }

        public Builder classes(Map<String, ReceiverSpec> classes) {
            this.classes = classes;
            return this;
        }

        public Builder methods(Map<String, List<ParamRef>> methods) {
            this.methods = methods;
            return this;
        }

        public Builder frameFormat(String frameFormat) {
            this.frameFormat = frameFormat;
            return this;
        }

        public Builder receiverFormat(String receiverFormat) {
            this.receiverFormat = receiverFormat;
            return this;
        }

        public Builder paramsFormat(String paramsFormat) {
            this.paramsFormat = paramsFormat;
            return this;
        }

        public Builder maxValueLength(int maxValueLength) {
            this.maxValueLength = maxValueLength;
            return this;
        }

        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        public Builder inPlaceModification(boolean inPlaceModification) {
            this.inPlaceModification = inPlaceModification;
            return this;
        }

        /**
         * In the order the first matching entry is looked for, e.g. a {@link LinkedHashMap}; empty (the default) for
         * none, {@code "**" = true} for all.
         */
        public Builder exceptions(Map<String, Boolean> exceptions) {
            this.exceptions = exceptions;
            return this;
        }

        /**
         * In the order the first matching entry is looked for, e.g. a {@link LinkedHashMap}; empty (the default) for
         * none, {@code "**" = true} for all.
         */
        public Builder annotations(Map<String, Boolean> annotations) {
            this.annotations = annotations;
            return this;
        }

        public AugmentorConfig build() {
            return new AugmentorConfig(classes, methods, frameFormat, receiverFormat, paramsFormat, maxValueLength, debug,
                    inPlaceModification, exceptions, annotations);
        }
    }

    /** Maps the parsed TOML onto {@link AugmentorConfig}; errors name the key and its line. */
    private static final class ConfigReader {

        private static final List<String> AUGMENT = List.of("augment");
        private static final List<String> CLASSES = List.of("augment", "receiver");
        private static final List<String> METHODS = List.of("augment", "params");
        private static final List<String> EXCEPTIONS = List.of("augment", "exceptions");
        private static final List<String> ANNOTATION_CLASSES = List.of("augment", "annotations");

        private static final List<String> ROOT_KEYS = List.of("debug", "inPlaceModification", "augment");
        private static final List<String> AUGMENT_KEYS = List.of("frameFormat", "receiverFormat", "paramsFormat", "maxValueLength",
                "exceptions", "annotations", "receiver", "params");

        /** A class name or class pattern: dotted segments of identifier characters and wildcards. */
        private static final Pattern CLASS_PART = Pattern.compile("[\\p{L}\\p{N}_$*?]+(\\.[\\p{L}\\p{N}_$*?]+)*");
        private static final Pattern METHOD_PART = Pattern.compile("[\\p{L}\\p{N}_$*?]+");
        /** For {@code "@"} in a key or value of {@code [augment.receiver]} or {@code [augment.params]}. */
        private static final String ANNOTATIONS_ELSEWHERE = "\"@\" is not allowed here: these tables name members and "
                + "parameters, and [augment.annotations] says where the annotations are read, e.g. \"com.acme.**\" = true";

        private static final String ALLOWED_CHARACTERS =
                "allowed are letters, digits, _, $ and the wildcards * (within a package or name), ** (across packages) and ?";

        private final TomlParseResult toml;
        private final String source;

        ConfigReader(TomlParseResult toml, String source) {
            this.toml = toml;
            this.source = source;
        }

        AugmentorConfig read() {
            checkKeys(List.of(), toml, ROOT_KEYS);
            TomlTable augment = table(AUGMENT);
            if (augment != null) {
                checkKeys(AUGMENT, augment, AUGMENT_KEYS);
            }

            Builder config = builder();
            Map<String, ReceiverSpec> classes = new LinkedHashMap<>();
            Map<String, List<String>> classPaths = new HashMap<>();
            for (Entry entry : entries(CLASSES)) {
                String key = unique(entryKey(entry.path(), CLASSES), entry.path(), classPaths);
                classes.put(key, classSpec(entry.path(), entry.value()));
            }
            config.classes(classes);
            Map<String, List<ParamRef>> methods = new LinkedHashMap<>();
            Map<String, List<String>> methodPaths = new HashMap<>();
            for (Entry entry : entries(METHODS)) {
                String key = unique(entryKey(entry.path(), METHODS), entry.path(), methodPaths);
                methods.put(key, paramRefs(entry.path(), entry.value()));
            }
            config.methods(methods);
            String frameFormat = value(plus(AUGMENT, "frameFormat"), String.class, "a string");
            if (frameFormat != null) {
                config.frameFormat(frameFormat);
            }
            String receiverFormat = value(plus(AUGMENT, "receiverFormat"), String.class, "a string");
            if (receiverFormat != null) {
                config.receiverFormat(receiverFormat);
            }
            String paramsFormat = value(plus(AUGMENT, "paramsFormat"), String.class, "a string");
            if (paramsFormat != null) {
                config.paramsFormat(paramsFormat);
            }
            Long maxValueLength = value(plus(AUGMENT, "maxValueLength"), Long.class, "an integer");
            if (maxValueLength != null) {
                config.maxValueLength(maxValueLength(maxValueLength));
            }
            config.exceptions(switches(EXCEPTIONS, "must be true to augment the exceptions of this class or class pattern, or "
                    + "false not to"));
            config.annotations(switches(ANNOTATION_CLASSES, "must be true to read the annotations of this class or class "
                    + "pattern, or false not to"));
            Boolean debug = value(List.of("debug"), Boolean.class, "true or false");
            if (debug != null) {
                config.debug(debug);
            }
            Boolean inPlaceModification = value(List.of("inPlaceModification"), Boolean.class, "true or false");
            if (inPlaceModification != null) {
                config.inPlaceModification(inPlaceModification);
            }
            AugmentorConfig result = config.build();
            checkFormats(result);
            return result;
        }

        /**
         * Parses the templates, so that every reader of the file rejects invalid ones when it loads it: also the build
         * plugin, which does not render frames itself.
         */
        private void checkFormats(AugmentorConfig config) {
            try {
                FrameFormat.create(config);
            } catch (ConfigException e) {
                // FrameFormat's messages start with the template's key, e.g. "paramsFormat must not contain '('".
                String message = e.getMessage();
                int space = message.indexOf(' ');
                throw error(plus(AUGMENT, space > 0 ? message.substring(0, space) : "frameFormat"), message);
            }
        }

        private void checkKeys(List<String> path, TomlTable table, List<String> allowed) {
            for (String key : table.keySet()) {
                if (!allowed.contains(key)) {
                    String where = path.isEmpty() ? "" : " in [" + name(path) + "]";
                    throw error(plus(path, key), "unknown key '" + key + "'" + where + "; allowed: " + String.join(", ", allowed));
                }
            }
        }

        private TomlTable table(List<String> path) {
            return value(path, TomlTable.class, "a table, e.g. [" + name(path) + "]");
        }

        private <T> T value(List<String> path, Class<T> type, String expected) {
            Object value = toml.get(path);
            if (value == null) {
                return null;
            }
            if (!type.isInstance(value)) {
                throw error(path, "'" + name(path) + "' must be " + expected);
            }
            return type.cast(value);
        }

        private record Entry(List<String> path, Object value) {
        }

        /**
         * The entries of the {@code [augment.receiver]}, {@code [augment.params]} or {@code [augment.exceptions]} table,
         * with their full key paths, in the order of the file. Key paths make quoted ({@code "com.acme.Order"}) and
         * unquoted ({@code com.acme.Order}, i.e. nested tables) class names equivalent.
         */
        private List<Entry> entries(List<String> table) {
            TomlTable content = table(table);
            if (content == null) {
                return List.of();
            }
            List<Entry> entries = new ArrayList<>();
            for (List<String> path : content.keyPathSet()) {
                List<String> fullPath = new ArrayList<>(table);
                fullPath.addAll(path);
                entries.add(new Entry(fullPath, Objects.requireNonNull(content.get(path))));
            }
            // In the order of the file: tomlj's tables do not keep it, and in all three tables the first matching entry decides.
            entries.sort(Comparator.comparing((Entry entry) -> toml.inputPositionOf(entry.path()),
                    Comparator.nullsLast(Comparator.comparingInt(TomlPosition::line).thenComparingInt(TomlPosition::column))));
            return entries;
        }

        /**
         * The key, unless another entry of the same table already names it: {@code "com.acme.Order"} and
         * {@code com.acme.Order} are different TOML keys for the same class, as are {@code "com.acme.Order::a,b"},
         * {@code "com.acme.Order::a, b"} and {@code "com.acme.Order::b, a"} for the same methods, and neither may
         * silently win.
         */
        private String unique(String key, List<String> path, Map<String, List<String>> seen) {
            List<String> first = seen.putIfAbsent(sameAs(key), path);
            if (first != null) {
                TomlPosition position = toml.inputPositionOf(first);
                String where = position != null ? " on line " + position.line() : "";
                String same = key.contains(METHOD_SEPARATOR)
                        ? "keys that differ in spaces or in the order of their methods name the same methods"
                        : "quoted and unquoted keys name the same class";
                throw error(path, "'" + key + "' is configured twice; it is already configured" + where + " (" + same + ")");
            }
            return key;
        }

        /** What a key names, whatever the order of its methods: {@code "com.acme.Order::b, a"} as {@code "com.acme.Order::a, b"}. */
        private static String sameAs(String key) {
            int separator = key.indexOf(METHOD_SEPARATOR);
            if (separator < 0) {
                return key;
            }
            List<String> methods = new ArrayList<>(List.of(key.substring(separator + METHOD_SEPARATOR.length()).split(", ")));
            methods.sort(null);
            return key.substring(0, separator) + METHOD_SEPARATOR + String.join(", ", methods);
        }

        private static String target(List<String> path, List<String> table) {
            return String.join(".", path.subList(table.size(), path.size()));
        }

        /** The entries of {@code [augment.exceptions]} or {@code [augment.annotations]}: a class part, true or false. */
        private Map<String, Boolean> switches(List<String> table, String expected) {
            Map<String, Boolean> switches = new LinkedHashMap<>();
            Map<String, List<String>> paths = new HashMap<>();
            for (Entry entry : entries(table)) {
                String key = unique(classKey(entry.path(), table), entry.path(), paths);
                if (!(entry.value() instanceof Boolean on)) {
                    throw error(entry.path(), expected + ", was " + entry.value());
                }
                switches.put(key, on);
            }
            return switches;
        }

        /**
         * An {@code [augment.exceptions]} or {@code [augment.annotations]} key: a class name, optionally followed by
         * {@code +}, or a class pattern.
         */
        private String classKey(List<String> path, List<String> table) {
            String target = target(path, table);
            String example = table.equals(EXCEPTIONS)
                    ? "\"com.acme.FlowException\", \"java.io.IOException+\" with its subclasses"
                    : "\"com.acme.Order\", \"com.acme.Order+\" with its subclasses";
            checkClassPart(path, target, () -> error(path, "[" + name(table) + "] keys must name a class or a class pattern, "
                    + "e.g. " + example + ", or \"com.acme.**\"; " + ALLOWED_CHARACTERS));
            return target;
        }

        /** A class name, optionally followed by {@code +}, or a class pattern, which takes no {@code +}. */
        private void checkClassPart(List<String> path, String classPart, Supplier<ConfigException> invalid) {
            boolean subclasses = classPart.endsWith(SUBCLASSES);
            String className = subclasses ? classPart.substring(0, classPart.length() - SUBCLASSES.length()) : classPart;
            if (!CLASS_PART.matcher(className).matches()) {
                throw invalid.get();
            }
            if (subclasses && isPattern(className)) {
                throw error(path, "+ after the class includes its subclasses, so it needs a class name without wildcards; "
                        + "a pattern such as \"" + className + "\" matches class names only");
            }
        }

        /**
         * An {@code [augment.receiver]} or {@code [augment.params]} key: a class name or pattern, {@code +} after a class
         * name for its subclasses, {@code ::} and the methods, kept with ", " between them, e.g.
         * {@code "com.acme.Order+::ship, cancel"} for {@code "com.acme.Order+::ship,cancel"}. Constructors
         * ({@code <init>}) only in {@code [augment.params]}, as they show no receiver members.
         */
        private String entryKey(List<String> path, List<String> table) {
            boolean constructors = table.equals(METHODS);
            String target = target(path, table);
            int separator = target.indexOf(METHOD_SEPARATOR);
            if (separator <= 0) {
                throw invalidKey(path, table);
            }
            String classPart = target.substring(0, separator);
            checkClassPart(path, classPart, () -> invalidKey(path, table));
            List<String> names = new ArrayList<>();
            for (String name : List.of(target.substring(separator + METHOD_SEPARATOR.length()).split(",", -1))) {
                String trimmed = name.trim();
                if (trimmed.equals(ANNOTATIONS)) {
                    throw error(path, ANNOTATIONS_ELSEWHERE);
                }
                if (!(METHOD_PART.matcher(trimmed).matches() || constructors && trimmed.equals(CONSTRUCTOR))) {
                    throw invalidKey(path, table);
                }
                if (names.contains(trimmed)) {
                    throw error(path, "'" + trimmed + "' is listed twice");
                }
                names.add(trimmed);
            }
            return classPart + METHOD_SEPARATOR + String.join(", ", names);
        }

        private ConfigException invalidKey(List<String> path, List<String> table) {
            return error(path, "[" + name(table) + "] keys must name a class and its methods separated by ::, e.g. "
                    + "\"com.acme.Order::ship, cancel*\" or \"com.acme.Order::*\"; + after the class name includes its "
                    + "subclasses (\"com.acme.Order+::*\"), a class pattern matches class names only (\"com.acme.**::*\")"
                    + (table.equals(METHODS) ? ", <init> stands for the constructors" : "") + "; " + ALLOWED_CHARACTERS);
        }

        private ReceiverSpec classSpec(List<String> path, Object value) {
            if (!(value instanceof String text)) {
                throw error(path, "must be a string: a field name (e.g. \"orderId\"), a method (e.g. \"getOrderId()\"), "
                        + "several of them separated by commas (e.g. \"tenant, getOrderId()\"), or \"-\" for no receiver "
                        + "members, was " + value);
            }
            if (text.contains(METHOD_SEPARATOR)) {
                throw error(path, "the methods go in the key: \"com.acme.Order::ship, cancel\" = \"orderId\"");
            }
            if (ANNOTATIONS.equals(text.trim())) {
                throw error(path, ANNOTATIONS_ELSEWHERE);
            }
            if (EXCLUDED.equals(text.trim())) {
                return new ReceiverSpec.Excluded();
            }
            List<ReceiverSpec.MemberSpec> members = new ArrayList<>();
            for (String member : items(path, text, "field or method")) {
                ReceiverSpec.MemberSpec spec = memberSpec(member);
                if (spec == null) {
                    throw error(path, "invalid receiver member '" + member + "': use a field name (e.g. \"orderId\") or a "
                            + "method (e.g. \"getOrderId()\"), several separated by commas");
                }
                if (members.stream().anyMatch(it -> it.memberName().equals(spec.memberName()))) {
                    throw error(path, "'" + member + "' is listed twice");
                }
                members.add(spec);
            }
            return members.size() == 1 ? members.get(0) : new ReceiverSpec.MemberList(members);
        }

        /** The items of a comma-separated value, trimmed; an empty item is an error. */
        private List<String> items(List<String> path, String text, String what) {
            List<String> items = new ArrayList<>();
            for (String item : text.split(",", -1)) {
                String trimmed = item.trim();
                if (trimmed.isEmpty()) {
                    throw error(path, text.isBlank() ? "must name at least one " + what
                            : "has an empty item in '" + text + "': separate the items with single commas");
                }
                items.add(trimmed);
            }
            return items;
        }

        /** A field name or a method, e.g. {@code "orderId"} or {@code "getOrderId()"}; {@code null} for anything else. */
        private static ReceiverSpec.MemberSpec memberSpec(Object value) {
            if (!(value instanceof String text) || !IDENTIFIER.matcher(removeCallSuffix(text)).matches()) {
                return null;
            }
            String name = removeCallSuffix(text);
            return text.endsWith("()") ? new ReceiverSpec.MethodSpec(name) : new ReceiverSpec.FieldSpec(name);
        }

        private static String removeCallSuffix(String text) {
            return text.endsWith("()") ? text.substring(0, text.length() - 2) : text;
        }

        private List<ParamRef> paramRefs(List<String> path, Object value) {
            if (!(value instanceof String text)) {
                throw error(path, "must be a string of parameter names and indexes separated by commas, e.g. \"order, 2, email#\" "
                        + "(# hashes the value), or \"-\" for none, was " + value);
            }
            if (ANNOTATIONS.equals(text.trim())) {
                throw error(path, ANNOTATIONS_ELSEWHERE);
            }
            if (EXCLUDED.equals(text.trim())) {
                return List.of(new ParamRef.Excluded());
            }
            List<ParamRef> refs = new ArrayList<>();
            for (String item : items(path, text, "parameter")) {
                ParamRef ref = paramRef(path, item);
                if (refs.stream().anyMatch(it -> sameParameter(it, ref))) {
                    throw error(path, "'" + (item.endsWith("#") ? item.substring(0, item.length() - 1) : item) + "' is listed twice");
                }
                refs.add(ref);
            }
            return refs;
        }

        /** The same name or the same index, hashed or not: {@code "email, email#"} lists {@code email} twice. */
        private static boolean sameParameter(ParamRef a, ParamRef b) {
            return a instanceof ParamRef.ByName x && b instanceof ParamRef.ByName y && x.name().equals(y.name())
                    || a instanceof ParamRef.ByIndex i && b instanceof ParamRef.ByIndex j && i.index() == j.index();
        }

        /** A name or an index, optionally followed by {@code #} to hash the value: {@code "email#"}, {@code "1#"}. */
        private ParamRef paramRef(List<String> path, String text) {
            boolean hashed = text.endsWith("#");
            String name = hashed ? text.substring(0, text.length() - 1) : text;
            if (IDENTIFIER.matcher(name).matches()) {
                return new ParamRef.ByName(name, hashed);
            }
            if (INDEX.matcher(name).matches() && Integer.parseInt(name) <= 255) {
                return new ParamRef.ByIndex(Integer.parseInt(name), hashed);
            }
            throw error(path, "invalid parameter '" + text + "': use a parameter name or a 0-based index from 0 to 255, "
                    + "optionally followed by # to hash the value, e.g. \"email#\" or \"1#\"");
        }

        private int maxValueLength(long value) {
            if (value < 2 || value > 10_000) {
                throw error(plus(AUGMENT, "maxValueLength"), "maxValueLength must be between 2 and 10000, was " + value);
            }
            return (int) value;
        }

        private static String name(List<String> path) {
            return String.join(".", path);
        }

        private static List<String> plus(List<String> path, String key) {
            List<String> result = new ArrayList<>(path);
            result.add(key);
            return result;
        }

        private ConfigException error(List<String> path, String message) {
            TomlPosition position = toml.inputPositionOf(path);
            String line = position != null ? ", line " + position.line() : "";
            return new ConfigException(source + line + ": " + message);
        }
    }
}
