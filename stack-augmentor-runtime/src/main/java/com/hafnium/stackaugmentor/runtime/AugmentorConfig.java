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
 * [instrument]                  # what gets instrumented: agent at class load, build plugin at build time
 * annotatedClasses = ["com.hafnium.**"]
 *
 * [instrument.classIds]
 * "com.thirdparty.Order" = "getOrderNumber()"
 *
 * [instrument.methodParams]      # "<class>.<method>", with * and ? as wildcards
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
 * <p>Immutable. Equality covers the configured values only.
 */
public final class AugmentorConfig {

    public static final String DEFAULT_FRAME_FORMAT = "{class}{receiver}.{method}{params}";
    public static final String DEFAULT_RECEIVER_FORMAT = "{$name=$id}";
    public static final String DEFAULT_PARAMS_FORMAT = "{$name=$id, ...}";
    public static final int DEFAULT_MAX_ID_LENGTH = 64;
    public static final int DEFAULT_MAX_PARAMS = 8;
    public static final String CONFIG_PROPERTY = "stackaugmentor.config";

    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");

    private final List<String> annotatedClasses;
    private final Map<String, IdSpec> ids;
    private final Map<String, List<ParamRef>> params;
    private final String frameFormat;
    private final String receiverFormat;
    private final String paramsFormat;
    private final int maxIdLength;
    private final int maxParams;
    private final boolean debug;

    private final List<Pattern> annotatedClassPatterns;

    /** An {@code [instrument.methodParams]} entry with wildcards, matched against every class and method. */
    private record PatternEntry(Pattern classPattern, Pattern methodPattern, List<ParamRef> refs) {
    }

    private final List<PatternEntry> paramPatterns;

    /** The defaults. */
    public AugmentorConfig() {
        this(List.of(), Map.of(), Map.of(), DEFAULT_FRAME_FORMAT, DEFAULT_RECEIVER_FORMAT, DEFAULT_PARAMS_FORMAT,
                DEFAULT_MAX_ID_LENGTH, DEFAULT_MAX_PARAMS, false);
    }

    /**
     * @param annotatedClasses packages (globs) where the annotations are honoured; empty means all packages
     * @param ids              receiver id sources by class name: the {@code [instrument.classIds]} table
     * @param params           parameter ids by {@code "<class>.<method>"}, possibly with wildcards: the
     *                         {@code [instrument.methodParams]} table
     * @param maxParams        the most parameter ids shown per frame
     */
    public AugmentorConfig(List<String> annotatedClasses, Map<String, IdSpec> ids, Map<String, List<ParamRef>> params,
                           String frameFormat, String receiverFormat, String paramsFormat, int maxIdLength, int maxParams,
                           boolean debug) {
        this.annotatedClasses = List.copyOf(annotatedClasses);
        this.ids = Collections.unmodifiableMap(new LinkedHashMap<>(ids));
        Map<String, List<ParamRef>> paramsCopy = new LinkedHashMap<>();
        params.forEach((target, refs) -> paramsCopy.put(target, List.copyOf(refs)));
        this.params = Collections.unmodifiableMap(paramsCopy);
        this.frameFormat = Objects.requireNonNull(frameFormat, "frameFormat");
        this.receiverFormat = Objects.requireNonNull(receiverFormat, "receiverFormat");
        this.paramsFormat = Objects.requireNonNull(paramsFormat, "paramsFormat");
        this.maxIdLength = maxIdLength;
        this.maxParams = maxParams;
        this.debug = debug;
        this.annotatedClassPatterns = this.annotatedClasses.stream().map(AugmentorConfig::globToRegex).toList();
        List<PatternEntry> patterns = new ArrayList<>();
        this.params.forEach((target, refs) -> {
            if (isPattern(target)) {
                int dot = target.lastIndexOf('.');
                patterns.add(new PatternEntry(globToRegex(target.substring(0, dot)), globToRegex(target.substring(dot + 1)), refs));
            }
        });
        this.paramPatterns = List.copyOf(patterns);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Packages (globs) where {@code @StackTraceId} is honoured; empty means all packages. */
    public List<String> annotatedClasses() {
        return annotatedClasses;
    }

    /** Receiver id sources by class name: the {@code [instrument.classIds]} table. */
    public Map<String, IdSpec> ids() {
        return ids;
    }

    /** Parameter ids by {@code "<class>.<method>"}, possibly with wildcards: the {@code [instrument.methodParams]} table. */
    public Map<String, List<ParamRef>> params() {
        return params;
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

    /** Whether the {@code @StackTraceId}, {@code @StackTraceParam} and {@code @StackTraceParams} annotations on this class are used. */
    public boolean honoursAnnotations(String className) {
        if (annotatedClassPatterns.isEmpty()) {
            return true;
        }
        for (Pattern pattern : annotatedClassPatterns) {
            if (pattern.matcher(className).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The parameters selected for a method by {@code [instrument.methodParams]}: the entry without wildcards for
     * exactly this class and method, then every entry with wildcards that matches. Parameters selected more than
     * once are shown once, in declaration order, so the order of this list does not matter.
     */
    public List<ParamRef> paramRefs(String className, String methodName) {
        List<ParamRef> exact = params.getOrDefault(className + "." + methodName, List.of());
        if (paramPatterns.isEmpty()) {
            return exact;
        }
        List<ParamRef> refs = null;
        for (PatternEntry entry : paramPatterns) {
            if (entry.classPattern().matcher(className).matches() && entry.methodPattern().matcher(methodName).matches()) {
                if (refs == null) {
                    refs = new ArrayList<>(exact);
                }
                refs.addAll(entry.refs());
            }
        }
        return refs != null ? refs : exact;
    }

    public boolean hasParamEntries(String className) {
        String prefix = className + ".";
        for (String target : params.keySet()) {
            if (!isPattern(target) && target.startsWith(prefix)) {
                return true;
            }
        }
        for (PatternEntry entry : paramPatterns) {
            if (entry.classPattern().matcher(className).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Whether an {@code [instrument.methodParams]} key has wildcards ({@code *} or {@code ?}). */
    public static boolean isPattern(String target) {
        return target.indexOf('*') >= 0 || target.indexOf('?') >= 0;
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
                && annotatedClasses.equals(that.annotatedClasses)
                && ids.equals(that.ids)
                && params.equals(that.params)
                && frameFormat.equals(that.frameFormat)
                && receiverFormat.equals(that.receiverFormat)
                && paramsFormat.equals(that.paramsFormat)
                && maxIdLength == that.maxIdLength
                && maxParams == that.maxParams
                && debug == that.debug;
    }

    @Override
    public int hashCode() {
        return Objects.hash(annotatedClasses, ids, params, frameFormat, receiverFormat, paramsFormat, maxIdLength, maxParams, debug);
    }

    @Override
    public String toString() {
        return "AugmentorConfig[annotatedClasses=" + annotatedClasses + ", ids=" + ids + ", params=" + params
                + ", frameFormat=" + frameFormat + ", receiverFormat=" + receiverFormat + ", paramsFormat=" + paramsFormat
                + ", maxIdLength=" + maxIdLength + ", maxParams=" + maxParams + ", debug=" + debug + "]";
    }

    /** Starts from the defaults; every setter replaces one value. */
    public static final class Builder {

        private List<String> annotatedClasses = List.of();
        private Map<String, IdSpec> ids = Map.of();
        private Map<String, List<ParamRef>> params = Map.of();
        private String frameFormat = DEFAULT_FRAME_FORMAT;
        private String receiverFormat = DEFAULT_RECEIVER_FORMAT;
        private String paramsFormat = DEFAULT_PARAMS_FORMAT;
        private int maxIdLength = DEFAULT_MAX_ID_LENGTH;
        private int maxParams = DEFAULT_MAX_PARAMS;
        private boolean debug;

        private Builder() {
        }

        public Builder annotatedClasses(List<String> annotatedClasses) {
            this.annotatedClasses = annotatedClasses;
            return this;
        }

        public Builder ids(Map<String, IdSpec> ids) {
            this.ids = ids;
            return this;
        }

        public Builder params(Map<String, List<ParamRef>> params) {
            this.params = params;
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
            return new AugmentorConfig(annotatedClasses, ids, params, frameFormat, receiverFormat, paramsFormat, maxIdLength, maxParams,
                    debug);
        }
    }

    /** Maps the parsed TOML onto {@link AugmentorConfig}; errors name the key and its line. */
    private static final class ConfigReader {

        private static final List<String> INSTRUMENT = List.of("instrument");
        private static final List<String> AUGMENT = List.of("augment");
        private static final List<String> CLASS_IDS = List.of("instrument", "classIds");
        private static final List<String> METHOD_PARAMS = List.of("instrument", "methodParams");

        private static final List<String> ROOT_KEYS = List.of("debug", "instrument", "augment");
        private static final List<String> INSTRUMENT_KEYS = List.of("annotatedClasses", "classIds", "methodParams");
        private static final List<String> AUGMENT_KEYS = List.of("frameFormat", "receiverFormat", "paramsFormat", "maxIdLength",
                "maxParams");

        /** Class part of an [instrument.methodParams] key: dotted segments of identifier characters and wildcards. */
        private static final Pattern CLASS_PART = Pattern.compile("[\\p{L}\\p{N}_$*?]+(\\.[\\p{L}\\p{N}_$*?]+)*");
        private static final Pattern METHOD_PART = Pattern.compile("[\\p{L}\\p{N}_$*?]+");

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
            List<String> annotatedClasses = stringArray(plus(INSTRUMENT, "annotatedClasses"));
            if (annotatedClasses != null) {
                config.annotatedClasses(annotatedClasses);
            }
            Map<String, IdSpec> ids = new LinkedHashMap<>();
            for (Entry entry : entries(CLASS_IDS)) {
                ids.put(target(entry.path(), CLASS_IDS), idSpec(entry.path(), entry.value()));
            }
            config.ids(ids);
            Map<String, List<ParamRef>> params = new LinkedHashMap<>();
            for (Entry entry : entries(METHOD_PARAMS)) {
                params.put(target(entry.path(), METHOD_PARAMS), paramRefs(entry.path(), entry.value()));
            }
            config.params(params);
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

        private List<String> stringArray(List<String> path) {
            TomlArray array = value(path, TomlArray.class, "an array of strings, e.g. [\"com.acme.**\"]");
            if (array == null) {
                return null;
            }
            List<String> strings = new ArrayList<>(array.size());
            for (Object item : array.toList()) {
                if (!(item instanceof String text)) {
                    throw error(path, "'" + name(path) + "' must be an array of strings");
                }
                strings.add(text);
            }
            return strings;
        }

        private record Entry(List<String> path, Object value) {
        }

        /**
         * The entries of the {@code [instrument.classIds]} or {@code [instrument.methodParams]} table, with their full
         * key paths. Key paths make quoted ({@code "com.acme.Order"}) and unquoted ({@code com.acme.Order}, i.e. nested
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

        private String target(List<String> path, List<String> table) {
            String target = String.join(".", path.subList(table.size(), path.size()));
            boolean classIds = table.equals(CLASS_IDS);
            if (classIds && target.isEmpty()) {
                throw error(path, "[" + name(table) + "] keys must name a class, e.g. \"com.acme.Order\"");
            }
            int dot = target.lastIndexOf('.');
            if (!classIds && (dot <= 0 || !CLASS_PART.matcher(target.substring(0, dot)).matches()
                    || !METHOD_PART.matcher(target.substring(dot + 1)).matches())) {
                throw error(path, "[" + name(table) + "] keys must name a class and a method, e.g. \"com.acme.OrderService.process\"; "
                        + "allowed are letters, digits, _, $ and the wildcards * (within a package or name), ** (across packages) and ?");
            }
            return target;
        }

        private IdSpec idSpec(List<String> path, Object value) {
            if (!(value instanceof String text) || !IDENTIFIER.matcher(removeCallSuffix(text)).matches()) {
                throw error(path, "must be a field name (e.g. \"orderId\") or a method (e.g. \"getOrderId()\"), was " + value);
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
            if (!(value instanceof TomlArray array)) {
                throw error(path, "must be an array of parameter names and indexes, e.g. [\"order\", 2], or \"*\" for all parameters, was " + value);
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
