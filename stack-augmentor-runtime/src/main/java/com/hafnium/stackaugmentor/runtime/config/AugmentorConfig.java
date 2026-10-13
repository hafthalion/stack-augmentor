package com.hafnium.stackaugmentor.runtime.config;

import com.hafnium.stackaugmentor.runtime.ids.FrameFormat;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.Optional;
import java.util.function.Predicate;
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
 * receiverFormat = "{$name=$id, ...}"
 * paramsFormat = "{$name=$id, ...}"
 * maxIdLength = 64
 *
 * [augment.exceptions]       # which throwables get ids: true or false, the first matching entry wins; a class name
 *                            # matches its subclasses too, a pattern the runtime class only; a throwable that no
 *                            # entry matches gets none, so without the table none do: "**" = true for all
 * "com.acme.ControlFlowException" = false
 * "com.acme.**" = true
 * "java.io.IOException" = true   # and its subclasses
 *
 * [augment.receiver]         # receiver ids: a field, a "method()", several separated by commas, "@" for the
 *                            # @StackTraceId members, "-" for none; the first matching entry wins, a class name matches its
 *                            # subclasses too, a pattern the class only; ":: m1, m2" limits the ids to these methods
 * "com.hafnium.generated.**" = "-"
 * "com.hafnium.**" = "@"
 * "com.thirdparty.Order" = "getOrderNumber()"
 * "com.thirdparty.OrderLine" = "tenant, lineId()"
 * "com.thirdparty.Invoice" = "number :: pay, cancel*"   # only frames of pay and cancel... show the number
 *
 * [augment.params]           # parameter ids: names and indexes separated by commas ("#" after one hashes it), "@"
 *                            # for the annotations, "-" for none; the first matching entry wins
 * "com.hafnium.**.*" = "@"
 * "com.thirdparty.Shipment.<init>" = "orderId"   # constructors, also matched by wildcards such as ".*"
 * "com.thirdparty.OrderService.process" = "order, 2, email#, 3#"
 * "com.thirdparty.**.AuditRepository.*" = "-"
 * "com.thirdparty.**.*Repository.find*" = "0"
 * }</pre>
 *
 * <p>The two tables are independent: {@code [augment.receiver]} decides the receiver ids, {@code [augment.params]} the
 * parameter ids. Which methods get instrumented follows from both. Keys may use wildcards: {@code *} within one
 * package segment (or name), {@code **} across segments, {@code ?} one character. Where entries of
 * {@code [augment.receiver]} or {@code [augment.params]} overlap, the first in the file decides, see
 * {@link #classEntry(Class)} and {@link #paramRefs}. Immutable. Equality covers the
 * configured values only.
 */
public final class AugmentorConfig {

    /** The method name of constructors, in {@code [augment.params]} keys as in stack traces: {@code "com.acme.Order.<init>"}. */
    public static final String CONSTRUCTOR = "<init>";

    public static final String DEFAULT_FRAME_FORMAT = "$class$receiver.$method$params";
    public static final String DEFAULT_RECEIVER_FORMAT = "{$name=$id, ...}";
    public static final String DEFAULT_PARAMS_FORMAT = "{$name=$id, ...}";
    public static final int DEFAULT_MAX_ID_LENGTH = 64;
    public static final String CONFIG_PROPERTY = "stackaugmentor.config";
    /** Overrides {@code inPlaceModification} of the file: {@code -Dstackaugmentor.inPlaceModification=true}. */
    public static final String IN_PLACE_MODIFICATION_PROPERTY = "stackaugmentor.inPlaceModification";

    /** The value that stands for "use the annotations", in both tables. */
    public static final String ANNOTATIONS = "@";

    /** The value that stands for "ignore", in both tables. */
    public static final String EXCLUDED = "-";

    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");
    private static final Pattern INDEX = Pattern.compile("[0-9]{1,3}");

    private final Map<String, IdSpec> classes;
    private final Map<String, List<String>> receiverMethods;
    private final Map<String, List<ParamRef>> methods;
    private final String frameFormat;
    private final String receiverFormat;
    private final String paramsFormat;
    private final int maxIdLength;
    private final boolean debug;
    private final boolean inPlaceModification;
    private final Map<String, Boolean> exceptions;

    /**
     * An entry of {@code [augment.exceptions]} or {@code [augment.receiver]}: a class pattern, which matches the name of
     * the class only, or for a class name without wildcards {@code null}, which matches the class and its subclasses.
     */
    private record ClassKey<V>(String key, Pattern pattern, V value) {

        static <V> List<ClassKey<V>> of(Map<String, V> table) {
            return table.entrySet().stream()
                    .map(entry -> new ClassKey<>(entry.getKey(), isPattern(entry.getKey()) ? globToRegex(entry.getKey()) : null,
                            entry.getValue()))
                    .toList();
        }

        /** The first entry that matches, in the order of the file, or {@code null}. */
        static <V> ClassKey<V> first(List<ClassKey<V>> entries, String className, Predicate<String> isA) {
            for (ClassKey<V> entry : entries) {
                if (entry.pattern() != null ? entry.pattern().matcher(className).matches() : isA.test(entry.key())) {
                    return entry;
                }
            }
            return null;
        }
    }

    /** The {@code [augment.exceptions]} entries, in the order of the file. */
    private final List<ClassKey<Boolean>> exceptionKeys;

    /** Per throwable class, whether it gets ids: the value of the first {@code [augment.exceptions]} entry it matches. */
    private final ClassValue<Boolean> augmentedExceptions = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            ClassKey<Boolean> entry = ClassKey.first(exceptionKeys, type.getName(), name -> isA(type, name));
            return entry != null && entry.value();
        }
    };

    /**
     * An {@code [augment.receiver]} entry: the key as written, the id source it names, and the methods whose frames show
     * the ids, empty for all ({@code "id :: ship, cancel"} names {@code ship} and {@code cancel}).
     */
    public static final class ClassEntry {

        private final String key;
        private final IdSpec spec;
        private final List<String> methods;
        private final List<Pattern> methodPatterns;

        public ClassEntry(String key, IdSpec spec, List<String> methods) {
            this.key = Objects.requireNonNull(key, "key");
            this.spec = Objects.requireNonNull(spec, "spec");
            this.methods = List.copyOf(methods);
            this.methodPatterns = this.methods.stream().map(AugmentorConfig::globToRegex).toList();
        }

        public String key() {
            return key;
        }

        public IdSpec spec() {
            return spec;
        }

        /** The method names or patterns after {@code ::}, in the order of the file; empty for all methods. */
        public List<String> methods() {
            return methods;
        }

        /** Whether the key has wildcards. */
        public boolean isPattern() {
            return AugmentorConfig.isPattern(key);
        }

        /** Whether frames of this method show the receiver ids: all methods without {@code ::}, else the named ones. */
        public boolean appliesTo(String methodName) {
            if (methodPatterns.isEmpty()) {
                return true;
            }
            for (Pattern pattern : methodPatterns) {
                if (pattern.matcher(methodName).matches()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return key + "=" + specText(spec) + (methods.isEmpty() ? "" : " :: " + String.join(", ", methods));
        }
    }

    /** The {@code [augment.receiver]} entries, in the order of the file. */
    private final List<ClassKey<ClassEntry>> classKeys;

    /** Per class, its deciding {@code [augment.receiver]} entry, if any. */
    private final ClassValue<Optional<ClassEntry>> classEntries = new ClassValue<>() {
        @Override
        protected Optional<ClassEntry> computeValue(Class<?> type) {
            return Optional.ofNullable(classEntry(type.getName(), name -> isA(type, name)));
        }
    };

    /** An {@code [augment.params]} entry: its class and method parts, each a name or a pattern. */
    private record MethodPattern(String key, Pattern classPattern, Pattern methodPattern, List<ParamRef> refs) {
    }

    /** The {@code [augment.params]} entries, in the order of the file. */
    private final List<MethodPattern> methodPatterns;

    /** The defaults: no class or method entries, so nothing gets ids. */
    public AugmentorConfig() {
        this(Map.of(), Map.of(), Map.of(), DEFAULT_FRAME_FORMAT, DEFAULT_RECEIVER_FORMAT, DEFAULT_PARAMS_FORMAT,
                DEFAULT_MAX_ID_LENGTH, false, false, Map.of());
    }

    /**
     * @param classes   receiver id sources by class name or class pattern: the {@code [augment.receiver]} table
     * @param receiverMethods the methods that show the receiver ids, by the keys of {@code classes}: the names or
     *                  patterns after {@code ::}; a key without them (or with an empty list) applies to all methods
     * @param methods   parameter ids by {@code "<class>.<method>"}, possibly with wildcards: the
     *                  {@code [augment.params]} table
     * @param exceptions whether throwables get ids, by class name (with subclasses) or class pattern (of the runtime
     *                  class), in the order the first matching entry is looked for: the {@code [augment.exceptions]}
     *                  table; empty for none, {@code "**" = true} for all
     */
    public AugmentorConfig(Map<String, IdSpec> classes, Map<String, List<String>> receiverMethods, Map<String, List<ParamRef>> methods,
                           String frameFormat, String receiverFormat, String paramsFormat, int maxIdLength,
                           boolean debug, boolean inPlaceModification, Map<String, Boolean> exceptions) {
        this.classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        Map<String, List<String>> receiverMethodsCopy = new LinkedHashMap<>();
        receiverMethods.forEach((key, names) -> {
            if (!names.isEmpty()) {
                receiverMethodsCopy.put(key, List.copyOf(names));
            }
        });
        this.receiverMethods = Collections.unmodifiableMap(receiverMethodsCopy);
        Map<String, List<ParamRef>> methodsCopy = new LinkedHashMap<>();
        methods.forEach((target, refs) -> methodsCopy.put(target, List.copyOf(refs)));
        this.methods = Collections.unmodifiableMap(methodsCopy);
        this.frameFormat = Objects.requireNonNull(frameFormat, "frameFormat");
        this.receiverFormat = Objects.requireNonNull(receiverFormat, "receiverFormat");
        this.paramsFormat = Objects.requireNonNull(paramsFormat, "paramsFormat");
        this.maxIdLength = maxIdLength;
        this.debug = debug;
        this.inPlaceModification = inPlaceModification;
        this.exceptions = Collections.unmodifiableMap(new LinkedHashMap<>(exceptions));
        this.exceptionKeys = ClassKey.of(this.exceptions);
        Map<String, ClassEntry> classEntries = new LinkedHashMap<>();
        this.classes.forEach((key, spec) -> classEntries.put(key, new ClassEntry(key, spec,
                this.receiverMethods.getOrDefault(key, List.of()))));
        this.classKeys = ClassKey.of(classEntries);

        List<MethodPattern> methodPatterns = new ArrayList<>();
        this.methods.forEach((target, refs) -> {
            int dot = target.lastIndexOf('.');
            methodPatterns.add(new MethodPattern(target, globToRegex(target.substring(0, dot)), globToRegex(target.substring(dot + 1)),
                    refs));
        });
        this.methodPatterns = List.copyOf(methodPatterns);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Receiver id sources by class name or class pattern: the {@code [augment.receiver]} table. */
    public Map<String, IdSpec> classes() {
        return classes;
    }

    /**
     * The methods that show the receiver ids, by {@code [augment.receiver]} key: the names or patterns after {@code ::}.
     * Keys without {@code ::} are missing; they apply to all methods.
     */
    public Map<String, List<String>> receiverMethods() {
        return receiverMethods;
    }

    /** Parameter ids by {@code "<class>.<method>"}, possibly with wildcards: the {@code [augment.params]} table. */
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

    public int maxIdLength() {
        return maxIdLength;
    }

    public boolean debug() {
        return debug;
    }

    /** The {@code [augment.exceptions]} entries, in the order of the file; empty when no throwable gets ids. */
    public Map<String, Boolean> exceptions() {
        return exceptions;
    }

    /**
     * Whether frames of throwables of this runtime class get ids: the value of the first {@code [augment.exceptions]}
     * entry that matches decides, and a class that no entry matches gets none, so without entries none does. An entry without
     * wildcards matches its class and the subclasses, as {@code instanceof} does; a pattern matches the name of the
     * runtime class only, as in {@code [augment.receiver]}.
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

    /** The {@code [augment.receiver]} entries for the debug log, e.g. {@code com.acme.**=@, com.acme.Order=getId()}. */
    public String classesDescription() {
        String text = classes.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + specText(entry.getValue())
                        + (receiverMethods.containsKey(entry.getKey()) ? " :: " + String.join(", ", receiverMethods.get(entry.getKey())) : ""))
                .collect(Collectors.joining(", "));
        return text.isEmpty() ? "none" : text;
    }

    /** An {@code [augment.receiver]} value as written, e.g. {@code getId()} or {@code [tenant, id()]}. */
    public static String specText(IdSpec spec) {
        return switch (spec) {
            case IdSpec.Annotations annotations -> ANNOTATIONS;
            case IdSpec.MethodSpec method -> method.memberName() + "()";
            case IdSpec.FieldSpec field -> field.memberName();
            case IdSpec.MemberList list -> list.members().stream().map(AugmentorConfig::specText)
                    .collect(Collectors.joining(", ", "[", "]"));
            case IdSpec.Excluded excluded -> EXCLUDED;
        };
    }

    /** The {@code [augment.params]} entries for the debug log, e.g. {@code com.acme.Order.process[order, #2]}. */
    public String methodsDescription() {
        String text = methods.entrySet().stream()
                .map(entry -> entry.getKey() + entry.getValue().stream()
                        .map(ref -> switch (ref) {
                            case ParamRef.ByName byName -> byName.name() + (byName.hashed() ? "#" : "");
                            case ParamRef.ByIndex byIndex -> "#" + byIndex.index() + (byIndex.hashed() ? "#" : "");
                            case ParamRef.Annotations annotations -> ANNOTATIONS;
                            case ParamRef.Excluded excluded -> EXCLUDED;
                        })
                        .collect(Collectors.joining(", ", "[", "]")))
                .collect(Collectors.joining(", "));
        return text.isEmpty() ? "none" : text;
    }

    /** Whether anything can be augmented: without class or method entries other than {@code "-"}, nothing is. */
    public boolean hasAugmentEntries() {
        return classes.values().stream().anyMatch(spec -> !(spec instanceof IdSpec.Excluded))
                || methods.values().stream().anyMatch(refs -> !refs.contains(new ParamRef.Excluded()));
    }

    /**
     * The deciding {@code [augment.receiver]} entry of a class, or {@code null}: the first entry in the order of the file
     * that matches. An entry without wildcards matches its class and the subclasses, as {@code instanceof} does, so
     * also the classes implementing an interface it names; a pattern matches the name of the class only. The class is
     * the one that declares a frame's method.
     */
    public ClassEntry classEntry(Class<?> type) {
        return classEntries.get(type).orElse(null);
    }

    /**
     * {@link #classEntry(Class)} for a class known by its name, e.g. one that is being instrumented.
     *
     * @param isA whether the class has the given name, or one of its superclasses or the interfaces it implements,
     *            directly or not, has it; see {@link #isA(Class, String)}
     */
    public ClassEntry classEntry(String className, Predicate<String> isA) {
        ClassKey<ClassEntry> entry = ClassKey.first(classKeys, className, isA);
        return entry != null ? entry.value() : null;
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
     * matches it, by the name of the class that declares the method and the method name. Later entries play no part,
     * so a {@code "-"} entry selects nothing even where a later entry would select parameters.
     * {@code [augment.receiver]} plays no part either.
     */
    public List<ParamRef> paramRefs(String className, String methodName) {
        for (MethodPattern entry : methodPatterns) {
            if (entry.classPattern().matcher(className).matches() && entry.methodPattern().matcher(methodName).matches()) {
                return entry.refs().contains(new ParamRef.Excluded()) ? List.of() : entry.refs();
            }
        }
        return List.of();
    }

    /**
     * Whether an {@code [augment.params]} entry other than {@code "-"} matches this class name for some method, so that
     * {@link #paramRefs} may select parameters of its methods.
     */
    public boolean hasParamEntries(String className) {
        for (MethodPattern pattern : methodPatterns) {
            if (!pattern.refs().contains(new ParamRef.Excluded()) && pattern.classPattern().matcher(className).matches()) {
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
     * or by the {@code stackaugmentor.config} system property. Without either, the defaults apply.
     */
    public static AugmentorConfig load(String agentArgs) {
        String location = location(agentArgs);
        return location != null ? load(Path.of(location)) : new AugmentorConfig();
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
                && receiverMethods.equals(that.receiverMethods)
                && methods.equals(that.methods)
                && frameFormat.equals(that.frameFormat)
                && receiverFormat.equals(that.receiverFormat)
                && paramsFormat.equals(that.paramsFormat)
                && maxIdLength == that.maxIdLength
                && debug == that.debug
                && inPlaceModification == that.inPlaceModification
                && exceptions.equals(that.exceptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classes, receiverMethods, methods, frameFormat, receiverFormat, paramsFormat, maxIdLength, debug, inPlaceModification,
                exceptions);
    }

    @Override
    public String toString() {
        return "AugmentorConfig[classes=" + classes + ", receiverMethods=" + receiverMethods + ", methods=" + methods
                + ", frameFormat=" + frameFormat + ", receiverFormat=" + receiverFormat + ", paramsFormat=" + paramsFormat
                + ", maxIdLength=" + maxIdLength + ", debug=" + debug + ", inPlaceModification=" + inPlaceModification + ", exceptions=" + exceptions + "]";
    }

    /** Starts from the defaults; every setter replaces one value. */
    public static final class Builder {

        private Map<String, IdSpec> classes = Map.of();
        private Map<String, List<String>> receiverMethods = Map.of();
        private Map<String, List<ParamRef>> methods = Map.of();
        private String frameFormat = DEFAULT_FRAME_FORMAT;
        private String receiverFormat = DEFAULT_RECEIVER_FORMAT;
        private String paramsFormat = DEFAULT_PARAMS_FORMAT;
        private int maxIdLength = DEFAULT_MAX_ID_LENGTH;
        private boolean debug;
        private boolean inPlaceModification;
        private Map<String, Boolean> exceptions = Map.of();

        private Builder() {
        }

        public Builder classes(Map<String, IdSpec> classes) {
            this.classes = classes;
            return this;
        }

        /** The names or patterns after {@code ::}, by {@code [augment.receiver]} key; a missing key applies to all methods. */
        public Builder receiverMethods(Map<String, List<String>> receiverMethods) {
            this.receiverMethods = receiverMethods;
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

        public Builder maxIdLength(int maxIdLength) {
            this.maxIdLength = maxIdLength;
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

        public AugmentorConfig build() {
            return new AugmentorConfig(classes, receiverMethods, methods, frameFormat, receiverFormat, paramsFormat, maxIdLength, debug,
                    inPlaceModification, exceptions);
        }
    }

    /** Maps the parsed TOML onto {@link AugmentorConfig}; errors name the key and its line. */
    private static final class ConfigReader {

        private static final List<String> AUGMENT = List.of("augment");
        private static final List<String> CLASSES = List.of("augment", "receiver");
        private static final List<String> METHODS = List.of("augment", "params");
        private static final List<String> EXCEPTIONS = List.of("augment", "exceptions");
        /** Separates the receiver ids from the methods that show them: {@code "orderId :: ship, cancel"}. */
        private static final String METHODS_SEPARATOR = "::";

        private static final List<String> ROOT_KEYS = List.of("debug", "inPlaceModification", "augment");
        private static final List<String> AUGMENT_KEYS = List.of("frameFormat", "receiverFormat", "paramsFormat", "maxIdLength",
                "exceptions", "receiver", "params");

        /** A class name or class pattern: dotted segments of identifier characters and wildcards. */
        private static final Pattern CLASS_PART = Pattern.compile("[\\p{L}\\p{N}_$*?]+(\\.[\\p{L}\\p{N}_$*?]+)*");
        private static final Pattern METHOD_PART = Pattern.compile("[\\p{L}\\p{N}_$*?]+");
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
            Map<String, IdSpec> classes = new LinkedHashMap<>();
            Map<String, List<String>> receiverMethods = new LinkedHashMap<>();
            Map<String, List<String>> classPaths = new HashMap<>();
            for (Entry entry : entries(CLASSES)) {
                String key = unique(classKey(entry.path(), CLASSES), entry.path(), classPaths);
                String ids = receiverText(entry.path(), entry.value());
                int separator = ids.indexOf(METHODS_SEPARATOR);
                IdSpec spec = classSpec(entry.path(), separator < 0 ? ids : ids.substring(0, separator));
                classes.put(key, spec);
                if (separator >= 0) {
                    if (spec instanceof IdSpec.Excluded) {
                        throw error(entry.path(), "\"-\" gives no receiver id, so it takes no methods after ::");
                    }
                    receiverMethods.put(key, receiverMethods(entry.path(), ids.substring(separator + METHODS_SEPARATOR.length())));
                }
            }
            config.classes(classes);
            config.receiverMethods(receiverMethods);
            Map<String, List<ParamRef>> methods = new LinkedHashMap<>();
            Map<String, List<String>> methodPaths = new HashMap<>();
            for (Entry entry : entries(METHODS)) {
                String key = unique(methodKey(entry.path()), entry.path(), methodPaths);
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
            Long maxIdLength = value(plus(AUGMENT, "maxIdLength"), Long.class, "an integer");
            if (maxIdLength != null) {
                config.maxIdLength(maxIdLength(maxIdLength));
            }
            Map<String, Boolean> exceptions = new LinkedHashMap<>();
            Map<String, List<String>> exceptionPaths = new HashMap<>();
            for (Entry entry : entries(EXCEPTIONS)) {
                String key = unique(classKey(entry.path(), EXCEPTIONS), entry.path(), exceptionPaths);
                if (!(entry.value() instanceof Boolean augmented)) {
                    throw error(entry.path(), "must be true to augment the exceptions of this class or class pattern, or "
                            + "false not to, was " + entry.value());
                }
                exceptions.put(key, augmented);
            }
            config.exceptions(exceptions);
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
            // In the order of the file: tomlj's tables do not keep it, and in [augment.exceptions] it decides.
            entries.sort(Comparator.comparing((Entry entry) -> toml.inputPositionOf(entry.path()),
                    Comparator.nullsLast(Comparator.comparingInt(TomlPosition::line).thenComparingInt(TomlPosition::column))));
            return entries;
        }

        /**
         * The key, unless another entry of the same table already names it: {@code "com.acme.Order"} and
         * {@code com.acme.Order} are different TOML keys for the same class, and neither may silently win.
         */
        private String unique(String key, List<String> path, Map<String, List<String>> seen) {
            List<String> first = seen.putIfAbsent(key, path);
            if (first != null) {
                TomlPosition position = toml.inputPositionOf(first);
                String where = position != null ? " on line " + position.line() : "";
                throw error(path, "'" + key + "' is configured twice; it is already configured" + where
                        + " (quoted and unquoted keys name the same class)");
            }
            return key;
        }

        private static String target(List<String> path, List<String> table) {
            return String.join(".", path.subList(table.size(), path.size()));
        }

        private String classKey(List<String> path, List<String> table) {
            String target = target(path, table);
            if (!CLASS_PART.matcher(target).matches()) {
                throw error(path, "[" + name(table) + "] keys must name a class or a class pattern, e.g. \"com.acme.Order\" or "
                        + "\"com.acme.**\"; " + ALLOWED_CHARACTERS);
            }
            return target;
        }

        private String methodKey(List<String> path) {
            String target = target(path, METHODS);
            int dot = target.lastIndexOf('.');
            if (dot <= 0 || !CLASS_PART.matcher(target.substring(0, dot)).matches()
                    || !(METHOD_PART.matcher(target.substring(dot + 1)).matches() || target.substring(dot + 1).equals(CONSTRUCTOR))) {
                throw error(path, "[" + name(METHODS) + "] keys must name a class and a method, e.g. \"com.acme.OrderService.process\", "
                        + "or <init> for the constructors; " + ALLOWED_CHARACTERS);
            }
            return target;
        }

        private String receiverText(List<String> path, Object value) {
            if (!(value instanceof String text)) {
                throw error(path, "must be a string: a field name (e.g. \"orderId\"), a method (e.g. \"getOrderId()\"), "
                        + "several of them separated by commas (e.g. \"tenant, getOrderId()\"), \"@\" for its @StackTraceId "
                        + "members, or \"-\" for no receiver id, optionally followed by :: and the methods that show them "
                        + "(e.g. \"orderId :: ship, cancel\"), was " + value);
            }
            return text;
        }

        /** The method names after {@code ::}: names or patterns with {@code *} and {@code ?}, separated by commas. */
        private List<String> receiverMethods(List<String> path, String text) {
            List<String> names = new ArrayList<>();
            for (String name : items(path, text, "method after ::")) {
                if (!METHOD_PART.matcher(name).matches()) {
                    throw error(path, "invalid method '" + name + "' after ::: use method names (e.g. \"ship\") or "
                            + "patterns (e.g. \"find*\"), separated by commas, without ()");
                }
                if (names.contains(name)) {
                    throw error(path, "'" + name + "' is listed twice");
                }
                names.add(name);
            }
            return names;
        }

        private IdSpec classSpec(List<String> path, String text) {
            if (ANNOTATIONS.equals(text.trim())) {
                return new IdSpec.Annotations();
            }
            if (EXCLUDED.equals(text.trim())) {
                return new IdSpec.Excluded();
            }
            List<IdSpec.MemberSpec> members = new ArrayList<>();
            for (String member : items(path, text, "field or method")) {
                IdSpec.MemberSpec spec = memberSpec(member);
                if (spec == null) {
                    throw error(path, "invalid receiver id '" + member + "': use a field name (e.g. \"orderId\") or a "
                            + "method (e.g. \"getOrderId()\"), several separated by commas");
                }
                if (members.stream().anyMatch(it -> it.memberName().equals(spec.memberName()))) {
                    throw error(path, "'" + member + "' is listed twice");
                }
                members.add(spec);
            }
            return members.size() == 1 ? members.get(0) : new IdSpec.MemberList(members);
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
        private static IdSpec.MemberSpec memberSpec(Object value) {
            if (!(value instanceof String text) || !IDENTIFIER.matcher(removeCallSuffix(text)).matches()) {
                return null;
            }
            String name = removeCallSuffix(text);
            return text.endsWith("()") ? new IdSpec.MethodSpec(name) : new IdSpec.FieldSpec(name);
        }

        private static String removeCallSuffix(String text) {
            return text.endsWith("()") ? text.substring(0, text.length() - 2) : text;
        }

        private List<ParamRef> paramRefs(List<String> path, Object value) {
            if (!(value instanceof String text)) {
                throw error(path, "must be a string of parameter names and indexes separated by commas, e.g. \"order, 2, email#\" "
                        + "(# hashes the value), \"@\" for the method's annotations, or \"-\" for none, was " + value);
            }
            if (ANNOTATIONS.equals(text.trim())) {
                return List.of(new ParamRef.Annotations());
            }
            if (EXCLUDED.equals(text.trim())) {
                return List.of(new ParamRef.Excluded());
            }
            List<ParamRef> refs = new ArrayList<>();
            for (String ref : items(path, text, "parameter")) {
                refs.add(paramRef(path, ref));
            }
            return refs;
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

        private int maxIdLength(long value) {
            if (value < 2 || value > 10_000) {
                throw error(plus(AUGMENT, "maxIdLength"), "maxIdLength must be between 2 and 10000, was " + value);
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
