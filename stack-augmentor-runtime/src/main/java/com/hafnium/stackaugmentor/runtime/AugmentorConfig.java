package com.hafnium.stackaugmentor.runtime;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The configuration, read from a TOML file:
 *
 * <pre>{@code
 * debug = false
 *
 * [instrument.classes]           # receiver ids: a field, a "method()", or "@" for the class's annotations
 * "com.hafnium.**" = "@"
 * "com.thirdparty.Order" = "getOrderNumber()"
 *
 * [instrument.methods]           # parameter ids: names and indexes, "*" for all, "@" for the annotations
 * "com.thirdparty.OrderService.process" = ["order", 2]
 * "com.thirdparty.**.*Repository.find*" = "*"
 *
 * [augment]                      # how frames look: at runtime, in both modes
 * frameFormat = "{class}{receiver}.{method}{params}"
 * receiverFormat = "{$name=$id}"
 * paramsFormat = "{$name=$id, ...}"
 * maxIdLength = 64
 * maxParams = 8
 * }</pre>
 *
 * <p>Keys of both tables may use wildcards: {@code *} within one package segment (or name), {@code **} across
 * segments, {@code ?} one character. Immutable. Equality covers the configured values only.
 */
public final class AugmentorConfig {

    public static final String DEFAULT_FRAME_FORMAT = "{class}{receiver}.{method}{params}";
    public static final String DEFAULT_RECEIVER_FORMAT = "{$name=$id}";
    public static final String DEFAULT_PARAMS_FORMAT = "{$name=$id, ...}";
    public static final int DEFAULT_MAX_ID_LENGTH = 64;
    public static final int DEFAULT_MAX_PARAMS = 4;
    public static final String CONFIG_PROPERTY = "stackaugmentor.config";

    /** The value that stands for "use the annotations", in both tables. */
    public static final String ANNOTATIONS = "@";

    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");

    private final Map<String, IdSpec> classes;
    private final Map<String, List<ParamRef>> methods;
    private final String frameFormat;
    private final String receiverFormat;
    private final String paramsFormat;
    private final int maxIdLength;
    private final int maxParams;
    private final boolean debug;

    /** An {@code [instrument.classes]} entry: the key as written, and the id source it names. */
    public record ClassEntry(String key, IdSpec spec) {

        /** Whether the key has wildcards. */
        public boolean isPattern() {
            return AugmentorConfig.isPattern(key);
        }
    }

    private record ClassPattern(Pattern pattern, ClassEntry entry) {
    }

    /** The {@code [instrument.classes]} entries with wildcards, most specific first. */
    private final List<ClassPattern> classPatterns;

    /** An {@code [instrument.methods]} entry with wildcards, matched against every class and method. */
    private record MethodPattern(Pattern classPattern, Pattern methodPattern, List<ParamRef> refs) {
    }

    private final List<MethodPattern> methodPatterns;

    /** The defaults: no class or method entries, so nothing gets ids. */
    public AugmentorConfig() {
        this(Map.of(), Map.of(), DEFAULT_FRAME_FORMAT, DEFAULT_RECEIVER_FORMAT, DEFAULT_PARAMS_FORMAT,
                DEFAULT_MAX_ID_LENGTH, DEFAULT_MAX_PARAMS, false);
    }

    /**
     * @param classes   receiver id sources by class name or class pattern: the {@code [instrument.classes]} table
     * @param methods   parameter ids by {@code "<class>.<method>"}, possibly with wildcards: the
     *                  {@code [instrument.methods]} table
     * @param maxParams the most parameter ids shown per frame
     */
    public AugmentorConfig(Map<String, IdSpec> classes, Map<String, List<ParamRef>> methods,
                           String frameFormat, String receiverFormat, String paramsFormat, int maxIdLength, int maxParams,
                           boolean debug) {
        this.classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        Map<String, List<ParamRef>> methodsCopy = new LinkedHashMap<>();
        methods.forEach((target, refs) -> methodsCopy.put(target, List.copyOf(refs)));
        this.methods = Collections.unmodifiableMap(methodsCopy);
        this.frameFormat = Objects.requireNonNull(frameFormat, "frameFormat");
        this.receiverFormat = Objects.requireNonNull(receiverFormat, "receiverFormat");
        this.paramsFormat = Objects.requireNonNull(paramsFormat, "paramsFormat");
        this.maxIdLength = maxIdLength;
        this.maxParams = maxParams;
        this.debug = debug;

        List<ClassPattern> classPatterns = new ArrayList<>();
        this.classes.forEach((key, spec) -> {
            if (isPattern(key)) {
                classPatterns.add(new ClassPattern(globToRegex(key), new ClassEntry(key, spec)));
            }
        });
        classPatterns.sort(Comparator.comparingInt((ClassPattern it) -> specificity(it.entry().key())).reversed()
                .thenComparing(it -> it.entry().key()));
        this.classPatterns = List.copyOf(classPatterns);

        List<MethodPattern> methodPatterns = new ArrayList<>();
        this.methods.forEach((target, refs) -> {
            if (isPattern(target)) {
                int dot = target.lastIndexOf('.');
                methodPatterns.add(new MethodPattern(globToRegex(target.substring(0, dot)), globToRegex(target.substring(dot + 1)), refs));
            }
        });
        this.methodPatterns = List.copyOf(methodPatterns);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Receiver id sources by class name or class pattern: the {@code [instrument.classes]} table. */
    public Map<String, IdSpec> classes() {
        return classes;
    }

    /** Parameter ids by {@code "<class>.<method>"}, possibly with wildcards: the {@code [instrument.methods]} table. */
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

    /** The most parameter ids shown per frame. */
    public int maxParams() {
        return maxParams;
    }

    public boolean debug() {
        return debug;
    }

    /** The {@code [instrument.classes]} entries for the debug log, e.g. {@code com.acme.**=@, com.acme.Order=getId()}. */
    public String classesDescription() {
        String text = classes.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + switch (entry.getValue()) {
                    case IdSpec.Annotations annotations -> ANNOTATIONS;
                    case IdSpec.MethodSpec method -> method.memberName() + "()";
                    case IdSpec.FieldSpec field -> field.memberName();
                })
                .collect(Collectors.joining(", "));
        return text.isEmpty() ? "none" : text;
    }

    /** The {@code [instrument.methods]} entries for the debug log, e.g. {@code com.acme.Order.process[order, #2]}. */
    public String methodsDescription() {
        String text = methods.entrySet().stream()
                .map(entry -> entry.getKey() + entry.getValue().stream()
                        .map(ref -> switch (ref) {
                            case ParamRef.ByName byName -> byName.name();
                            case ParamRef.ByIndex byIndex -> "#" + byIndex.index();
                            case ParamRef.All all -> "*";
                            case ParamRef.Annotations annotations -> ANNOTATIONS;
                        })
                        .collect(Collectors.joining(", ", "[", "]")))
                .collect(Collectors.joining(", "));
        return text.isEmpty() ? "none" : text;
    }

    /** Whether anything can be augmented: without class or method entries, nothing is. */
    public boolean hasInstrumentEntries() {
        return !classes.isEmpty() || !methods.isEmpty();
    }

    /**
     * The most specific {@code [instrument.classes]} entry that matches exactly this class name, or {@code null}:
     * an entry without wildcards, otherwise the pattern with the most characters other than {@code *} and
     * {@code ?}, ties broken by key. Superclasses are not looked at; the callers walk the hierarchy.
     */
    public ClassEntry classEntry(String className) {
        IdSpec exact = classes.get(className);
        if (exact != null) {
            return new ClassEntry(className, exact);
        }
        for (ClassPattern pattern : classPatterns) {
            if (pattern.pattern().matcher(className).matches()) {
                return pattern.entry();
            }
        }
        return null;
    }

    /**
     * The parameters selected for a method by {@code [instrument.methods]}: the entry without wildcards for
     * exactly this class and method, then every entry with wildcards that matches. Parameters selected more than
     * once are shown once, in declaration order, so the order of this list does not matter.
     */
    public List<ParamRef> paramRefs(String className, String methodName) {
        List<ParamRef> exact = methods.getOrDefault(className + "." + methodName, List.of());
        if (methodPatterns.isEmpty()) {
            return exact;
        }
        List<ParamRef> refs = null;
        for (MethodPattern entry : methodPatterns) {
            if (entry.classPattern().matcher(className).matches() && entry.methodPattern().matcher(methodName).matches()) {
                if (refs == null) {
                    refs = new ArrayList<>(exact);
                }
                refs.addAll(entry.refs());
            }
        }
        return refs != null ? refs : exact;
    }

    public boolean hasMethodEntries(String className) {
        String prefix = className + ".";
        for (String target : methods.keySet()) {
            if (!isPattern(target) && target.startsWith(prefix)) {
                return true;
            }
        }
        for (MethodPattern entry : methodPatterns) {
            if (entry.classPattern().matcher(className).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a key of {@code [instrument.classes]} or {@code [instrument.methods]} has wildcards ({@code *} or {@code ?}). */
    public static boolean isPattern(String key) {
        return key.indexOf('*') >= 0 || key.indexOf('?') >= 0;
    }

    /** The number of characters of a key other than the wildcards: the more, the more specific. */
    static int specificity(String key) {
        int literal = 0;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c != '*' && c != '?') {
                literal++;
            }
        }
        return literal;
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
                && methods.equals(that.methods)
                && frameFormat.equals(that.frameFormat)
                && receiverFormat.equals(that.receiverFormat)
                && paramsFormat.equals(that.paramsFormat)
                && maxIdLength == that.maxIdLength
                && maxParams == that.maxParams
                && debug == that.debug;
    }

    @Override
    public int hashCode() {
        return Objects.hash(classes, methods, frameFormat, receiverFormat, paramsFormat, maxIdLength, maxParams, debug);
    }

    @Override
    public String toString() {
        return "AugmentorConfig[classes=" + classes + ", methods=" + methods
                + ", frameFormat=" + frameFormat + ", receiverFormat=" + receiverFormat + ", paramsFormat=" + paramsFormat
                + ", maxIdLength=" + maxIdLength + ", maxParams=" + maxParams + ", debug=" + debug + "]";
    }

    /** Starts from the defaults; every setter replaces one value. */
    public static final class Builder {

        private Map<String, IdSpec> classes = Map.of();
        private Map<String, List<ParamRef>> methods = Map.of();
        private String frameFormat = DEFAULT_FRAME_FORMAT;
        private String receiverFormat = DEFAULT_RECEIVER_FORMAT;
        private String paramsFormat = DEFAULT_PARAMS_FORMAT;
        private int maxIdLength = DEFAULT_MAX_ID_LENGTH;
        private int maxParams = DEFAULT_MAX_PARAMS;
        private boolean debug;

        private Builder() {
        }

        public Builder classes(Map<String, IdSpec> classes) {
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

        public Builder maxIdLength(int maxIdLength) {
            this.maxIdLength = maxIdLength;
            return this;
        }

        public Builder maxParams(int maxParams) {
            this.maxParams = maxParams;
            return this;
        }

        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        public AugmentorConfig build() {
            return new AugmentorConfig(classes, methods, frameFormat, receiverFormat, paramsFormat, maxIdLength, maxParams,
                    debug);
        }
    }

    /** Maps the parsed TOML onto {@link AugmentorConfig}; errors name the key and its line. */
    private static final class ConfigReader {

        private static final List<String> INSTRUMENT = List.of("instrument");
        private static final List<String> AUGMENT = List.of("augment");
        private static final List<String> CLASSES = List.of("instrument", "classes");
        private static final List<String> METHODS = List.of("instrument", "methods");

        private static final List<String> ROOT_KEYS = List.of("debug", "instrument", "augment");
        private static final List<String> INSTRUMENT_KEYS = List.of("classes", "methods");
        private static final List<String> AUGMENT_KEYS = List.of("frameFormat", "receiverFormat", "paramsFormat", "maxIdLength",
                "maxParams");

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
            TomlTable instrument = table(INSTRUMENT);
            if (instrument != null) {
                checkKeys(INSTRUMENT, instrument, INSTRUMENT_KEYS);
            }
            TomlTable augment = table(AUGMENT);
            if (augment != null) {
                checkKeys(AUGMENT, augment, AUGMENT_KEYS);
            }

            Builder config = builder();
            Map<String, IdSpec> classes = new LinkedHashMap<>();
            for (Entry entry : entries(CLASSES)) {
                classes.put(classKey(entry.path()), classSpec(entry.path(), entry.value()));
            }
            config.classes(classes);
            Map<String, List<ParamRef>> methods = new LinkedHashMap<>();
            for (Entry entry : entries(METHODS)) {
                methods.put(methodKey(entry.path()), paramRefs(entry.path(), entry.value()));
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
            Long maxParams = value(plus(AUGMENT, "maxParams"), Long.class, "an integer");
            if (maxParams != null) {
                config.maxParams(maxParams(maxParams));
            }
            Boolean debug = value(List.of("debug"), Boolean.class, "true or false");
            if (debug != null) {
                config.debug(debug);
            }
            return config.build();
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
         * The entries of the {@code [instrument.classes]} or {@code [instrument.methods]} table, with their full key
         * paths. Key paths make quoted ({@code "com.acme.Order"}) and unquoted ({@code com.acme.Order}, i.e. nested
         * tables) class names equivalent.
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
            return entries;
        }

        private static String target(List<String> path, List<String> table) {
            return String.join(".", path.subList(table.size(), path.size()));
        }

        private String classKey(List<String> path) {
            String target = target(path, CLASSES);
            if (!CLASS_PART.matcher(target).matches()) {
                throw error(path, "[" + name(CLASSES) + "] keys must name a class or a class pattern, e.g. \"com.acme.Order\" or "
                        + "\"com.acme.**\"; " + ALLOWED_CHARACTERS);
            }
            return target;
        }

        private String methodKey(List<String> path) {
            String target = target(path, METHODS);
            int dot = target.lastIndexOf('.');
            if (dot <= 0 || !CLASS_PART.matcher(target.substring(0, dot)).matches()
                    || !METHOD_PART.matcher(target.substring(dot + 1)).matches()) {
                throw error(path, "[" + name(METHODS) + "] keys must name a class and a method, e.g. \"com.acme.OrderService.process\"; "
                        + ALLOWED_CHARACTERS);
            }
            return target;
        }

        private IdSpec classSpec(List<String> path, Object value) {
            if (ANNOTATIONS.equals(value)) {
                return new IdSpec.Annotations();
            }
            if (!(value instanceof String text) || !IDENTIFIER.matcher(removeCallSuffix(text)).matches()) {
                throw error(path, "must be a field name (e.g. \"orderId\"), a method (e.g. \"getOrderId()\") or \"@\" for the "
                        + "class's annotations, was " + value);
            }
            String name = removeCallSuffix(text);
            return text.endsWith("()") ? new IdSpec.MethodSpec(name) : new IdSpec.FieldSpec(name);
        }

        private static String removeCallSuffix(String text) {
            return text.endsWith("()") ? text.substring(0, text.length() - 2) : text;
        }

        private List<ParamRef> paramRefs(List<String> path, Object value) {
            if ("*".equals(value)) {
                return List.of(new ParamRef.All());
            }
            if (ANNOTATIONS.equals(value)) {
                return List.of(new ParamRef.Annotations());
            }
            if (!(value instanceof TomlArray array)) {
                throw error(path, "must be an array of parameter names and indexes, e.g. [\"order\", 2], \"*\" for all parameters, "
                        + "or \"@\" for the method's annotations, was " + value);
            }
            if (array.size() == 0) {
                throw error(path, "must list at least one parameter");
            }
            List<ParamRef> refs = new ArrayList<>(array.size());
            for (Object ref : array.toList()) {
                if (ref instanceof String name && IDENTIFIER.matcher(name).matches()) {
                    refs.add(new ParamRef.ByName(name));
                } else if (ref instanceof Long index && index >= 0 && index <= 255) {
                    refs.add(new ParamRef.ByIndex(index.intValue()));
                } else {
                    throw error(path, "invalid parameter '" + ref + "': use a parameter name or a 0-based index");
                }
            }
            return refs;
        }

        private int maxParams(long value) {
            if (value < 1 || value > 255) {
                throw error(plus(AUGMENT, "maxParams"), "maxParams must be between 1 and 255, was " + value);
            }
            return (int) value;
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
