package com.hafnium.stackaugmentor.runtime.config;

import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.WeakIdentityMap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The configurations of the classes of one JVM. A class uses these configurations, in this order (see
 * {@link AugmentorConfig#layered}):
 *
 * <ol>
 *     <li>the global configuration given to the JVM: the agent's file or {@code -Dstackaugmentor.config};</li>
 *     <li>the {@value AugmentorConfig#CONTEXT_RESOURCE} of the nearest class loader, from the class's own upwards, that has
 *     one of its own, one that its parent does not see; without one, that of the system class loader, if any. A class
 *     loader may have one only;</li>
 *     <li>the {@value AugmentorConfig#LOCAL_RESOURCE} of the jar or the directory that the class comes from, if it has
 *     one of its own: a class loader may have several, one per jar or directory;</li>
 *     <li>the built-in configuration.</li>
 * </ol>
 *
 * <p>The global configurations of the JVM and of the system class loader are read at once, and an invalid one is
 * thrown. Those of the other class loaders, and all local ones, are read once per class loader, when a class of it first
 * needs them, and kept as long as the class loader lives. An invalid one is printed as an error, and the classes it
 * applies to are not augmented.
 *
 * @param <T> what the component makes of a configuration: the values it needs, never classes of the class loader, which
 *            would keep it alive
 */
public final class ClassLoaderConfigs<T> {

    /** The local configuration file of a jar or directory. */
    public static final String RESOURCE = AugmentorConfig.LOCAL_RESOURCE;

    /** The global configuration file of a class loader. */
    public static final String CONTEXT_RESOURCE = AugmentorConfig.CONTEXT_RESOURCE;

    private final String component;
    /** Null where every class uses the base configuration. */
    private final Function<AugmentorConfig, T> factory;
    /** The global configuration given to the JVM, or null. */
    private final AugmentorConfig argument;
    /** The global configuration of the system class loader, or null. */
    private final AugmentorConfig systemGlobal;
    private final AugmentorConfig baseConfig;
    private final T base;
    private final boolean builtInOnly;

    /** By class loader: its configurations, without one where its global configuration is invalid. */
    private final WeakIdentityMap<ClassLoader, Optional<LoaderConfigs<T>>> byLoader = new WeakIdentityMap<>();

    /** The class loaders whose configurations this thread is reading: one that it asks for again gets none. */
    private final ThreadLocal<List<ClassLoader>> reading = ThreadLocal.withInitial(ArrayList::new);

    /** Per class, what its configuration gives, empty where it is invalid. */
    private final ClassValue<Optional<T>> byClass = new ClassValue<>() {
        @Override
        protected Optional<T> computeValue(Class<?> type) {
            return Optional.ofNullable(forType(type.getClassLoader(), type.getName(), type.getProtectionDomain(), localConfiguration(type)));
        }
    };

    /**
     * The configurations of one class loader.
     *
     * @param global       its global configuration, or that of the nearest class loader above it that has one; null for
     *                     that of the system class loader
     * @param withoutLocal what the classes without a local configuration use
     * @param locals       the local configurations by {@link #source}, empty where one is invalid
     * @param sources      the {@link #source}s of the local configurations by the {@link #hash} of their content, for
     *                     {@link LocalConfiguration}
     * @param missing      the hashes of {@link LocalConfiguration}s not found, warned about once
     */
    private record LoaderConfigs<T>(AugmentorConfig global, T withoutLocal, Map<String, Optional<AugmentorConfig>> locals,
                                    Map<String, String> sources, Map<String, Optional<T>> bySource, Set<String> missing) {
    }

    private ClassLoaderConfigs(String component, Function<AugmentorConfig, T> factory, AugmentorConfig argument,
                               AugmentorConfig systemGlobal, T base) {
        this.component = component;
        this.factory = factory;
        this.argument = argument;
        this.systemGlobal = systemGlobal;
        this.baseConfig = layered(null, null);
        this.base = base != null ? base : factory.apply(baseConfig);
        this.builtInOnly = argument == null && systemGlobal == null;
    }

    /**
     * Reads the global configuration of the system class loader, and describes both global configurations; an invalid
     * one is thrown.
     *
     * @param component        the component, for the messages
     * @param argument         the global configuration given to the JVM, or null
     * @param argumentLocation where it comes from, for the debug output
     * @param factory          makes what the component needs of a class's configuration
     */
    public static <T> ClassLoaderConfigs<T> create(String component, AugmentorConfig argument, String argumentLocation,
                                                   Function<AugmentorConfig, T> factory) {
        ClassLoader system = ClassLoader.getSystemClassLoader();
        URL resource = contextResource(system);
        AugmentorConfig systemGlobal = resource != null ? read(resource) : null;
        ClassLoaderConfigs<T> configs = new ClassLoaderConfigs<>(component, factory, argument, systemGlobal, null);
        Log.setDebug(configs.baseConfig.debug());
        if (argument != null) {
            Startup.describe(component, argumentLocation, argument);
        }
        if (systemGlobal != null) {
            Startup.describe(component, resource + " of the system class loader", systemGlobal);
        }
        if (configs.builtInOnly) {
            Startup.describe(component, AugmentorConfig.BUILT_IN_LOCATION, AugmentorConfig.builtIn());
        }
        Startup.warnIfNoExceptions(component, configs.baseConfig);
        return configs;
    }

    /** Every class uses {@code base}: for tests, and for the configurations given in code. */
    public static <T> ClassLoaderConfigs<T> fixed(T base) {
        return new ClassLoaderConfigs<>(null, null, null, null, base);
    }

    /** What the classes of the JVM itself use, and those of class loaders without configurations of their own. */
    public T base() {
        return base;
    }

    /**
     * The configuration of {@link #base()}: the global ones given to the JVM and of the system class loader, and the
     * built-in one. Its {@code [settings]}, which only the one given to the JVM may have, apply to the whole JVM.
     */
    public AugmentorConfig baseConfig() {
        return baseConfig;
    }

    /** Whether {@link #base()} is the built-in configuration only, as no global configuration was found. */
    public boolean builtInOnly() {
        return builtInOnly;
    }

    /** What the class's configuration gives, or null if one of its configurations is invalid. */
    public T forClass(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        if (factory == null || loader == null) {
            return base;
        }
        if (byLoader.get(loader) == null) {
            // Not cached: the class loader's configurations may be being read on this thread.
            return forType(loader, type.getName(), type.getProtectionDomain(), localConfiguration(type));
        }
        return byClass.get(type).orElse(null);
    }

    /**
     * What the configuration of a class gives, by its class loader, name and protection domain, e.g. while it is being
     * loaded; null if one of its configurations is invalid, or if those of its class loader are being read on this
     * thread, e.g. because reading them loads a class.
     */
    public T forType(ClassLoader loader, String className, ProtectionDomain protectionDomain) {
        return forType(loader, className, protectionDomain, null);
    }

    /**
     * {@link #forType(ClassLoader, String, ProtectionDomain)}, for a class whose build plugin named its local
     * configuration ({@link LocalConfiguration}): the {@link #hash} of its content, or null.
     */
    T forType(ClassLoader loader, String className, ProtectionDomain protectionDomain, String localConfiguration) {
        if (factory == null || loader == null) {
            return base;
        }
        LoaderConfigs<T> configs = configs(loader);
        if (configs == null) {
            return null;
        }
        String source = localConfiguration != null ? configs.sources().get(localConfiguration) : null;
        if (localConfiguration != null && source == null && configs.missing().add(localConfiguration)) {
            Log.warn(component + ": " + className + " was instrumented with a " + RESOURCE + " that class loader " + describe(loader)
                    + " does not have, e.g. one changed after the build, so its jar or directory decides which applies");
        }
        if (source == null && configs.locals().isEmpty()) {
            return configs.withoutLocal();
        }
        if (source == null) {
            source = source(loader, className, protectionDomain);
        }
        Optional<AugmentorConfig> local = source != null ? configs.locals().get(source) : null;
        if (local == null) {
            return configs.withoutLocal();
        }
        if (local.isEmpty()) {
            return null;
        }
        return configs.bySource().computeIfAbsent(source, key -> Optional.of(factory.apply(layered(configs.global(), local.get()))))
                .orElse(null);
    }

    /** Reads the configurations of the class loader now, if they were not yet read. */
    public void register(ClassLoader loader) {
        if (factory != null && loader != null) {
            configs(loader);
        }
    }

    /** Whether the base configuration, or one of those made so far, matches. */
    public boolean anyMatches(Predicate<T> predicate) {
        if (predicate.test(base)) {
            return true;
        }
        if (factory == null) {
            return false;
        }
        for (Optional<LoaderConfigs<T>> value : byLoader.values()) {
            if (value.isEmpty()) {
                continue;
            }
            LoaderConfigs<T> configs = value.get();
            if (configs.withoutLocal() != null && predicate.test(configs.withoutLocal())) {
                return true;
            }
            for (Optional<T> each : configs.bySource().values()) {
                if (each.isPresent() && predicate.test(each.get())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The configurations of a class's chain, without the ones that are null. */
    private AugmentorConfig layered(AugmentorConfig global, AugmentorConfig local) {
        List<AugmentorConfig> layers = new ArrayList<>();
        if (argument != null) {
            layers.add(argument);
        }
        AugmentorConfig loaderGlobal = global != null ? global : systemGlobal;
        if (loaderGlobal != null) {
            layers.add(loaderGlobal);
        }
        if (local != null) {
            layers.add(local);
        }
        layers.add(AugmentorConfig.builtIn());
        return AugmentorConfig.layered(layers);
    }

    /** The class loader's configurations, or null if its global configuration is invalid or being read on this thread. */
    private LoaderConfigs<T> configs(ClassLoader loader) {
        Optional<LoaderConfigs<T>> known = byLoader.get(loader);
        if (known != null) {
            return known.orElse(null);
        }
        List<ClassLoader> current = reading.get();
        if (current.contains(loader)) {
            return null;
        }
        current.add(loader);
        try {
            return byLoader.setIfAbsent(loader, Optional.ofNullable(read(loader))).orElse(null);
        } finally {
            current.remove(loader);
        }
    }

    private LoaderConfigs<T> read(ClassLoader loader) {
        AugmentorConfig global;
        if (loader == ClassLoader.getSystemClassLoader()) {
            global = null;
        } else {
            try {
                URL resource = contextResource(loader);
                global = resource != null ? readFor(loader, resource, bytes(resource)) : inherited(loader.getParent());
            } catch (ConfigException e) {
                Log.error(component + ": " + e.getMessage() + "; the classes of class loader " + describe(loader) + " are not augmented");
                return null;
            }
            if (global == INVALID) {
                return null;
            }
        }
        Map<String, Optional<AugmentorConfig>> locals = new HashMap<>();
        Map<String, String> sources = new HashMap<>();
        for (URL resource : ownResources(loader, RESOURCE)) {
            String source = source(resource);
            try {
                byte[] content = bytes(resource);
                sources.putIfAbsent(hash(content), source);
                locals.put(source, Optional.of(readFor(loader, resource, content)));
            } catch (ConfigException e) {
                Log.error(component + ": " + e.getMessage() + "; the classes of " + source + " are not augmented");
                locals.put(source, Optional.empty());
            }
        }
        T withoutLocal = global == null ? base : factory.apply(layered(global, null));
        return new LoaderConfigs<>(global, withoutLocal, Collections.unmodifiableMap(locals), Collections.unmodifiableMap(sources),
                new ConcurrentHashMap<>(), ConcurrentHashMap.newKeySet());
    }

    /** Stands for an invalid global configuration of a class loader above. */
    private static final AugmentorConfig INVALID = new AugmentorConfig();

    /** The global configuration that a class loader without one of its own uses: that of its parent, null for the system's. */
    private AugmentorConfig inherited(ClassLoader parent) {
        if (parent == null || parent == ClassLoader.getSystemClassLoader()) {
            return null;
        }
        LoaderConfigs<T> configs = configs(parent);
        return configs != null ? configs.global() : INVALID;
    }

    /** Reads a configuration file of a class loader, and describes it. */
    private AugmentorConfig readFor(ClassLoader loader, URL resource, byte[] content) {
        AugmentorConfig config = AugmentorConfig.parse(new String(content, StandardCharsets.UTF_8), resource.toString(), false);
        String location = resource + " of class loader " + describe(loader);
        Startup.describe(component, location, config);
        return config;
    }

    private static AugmentorConfig read(URL resource) {
        return AugmentorConfig.parse(new String(bytes(resource), StandardCharsets.UTF_8), resource.toString(), false);
    }

    /**
     * Reads without the JVM's cache of jar files, which would keep a jar open after its class loader is closed: on
     * Windows, it could then not be deleted or replaced, e.g. a webapp's jar on redeployment.
     */
    private static byte[] bytes(URL resource) {
        try {
            URLConnection connection = resource.openConnection();
            connection.setUseCaches(false);
            try (InputStream in = connection.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new ConfigException("cannot read " + resource + ": " + e);
        } catch (UncheckedIOException e) {
            throw new ConfigException("cannot read " + resource + ": " + e.getCause());
        }
    }

    /** What {@link LocalConfiguration} names a configuration file by: the SHA-256 of its content, in lowercase hex. */
    public static String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The {@link LocalConfiguration} of a class, or null. Compared by name, as the class may see another copy of the
     * annotation than this class does, e.g. that of the application while this one is the agent's.
     */
    static String localConfiguration(Class<?> type) {
        try {
            for (Annotation annotation : type.getDeclaredAnnotations()) {
                if (annotation.annotationType().getName().equals(LocalConfiguration.class.getName())) {
                    Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                    return value instanceof String hash ? hash : null;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.debug(() -> "cannot read the annotations of " + type.getName() + ": " + e);
        }
        return null;
    }

    /** The class loader's own {@value #CONTEXT_RESOURCE}, or null; several are an error. */
    private static URL contextResource(ClassLoader loader) {
        List<URL> resources = ownResources(loader, CONTEXT_RESOURCE);
        if (resources.size() > 1) {
            throw new ConfigException("class loader " + describe(loader) + " has several " + CONTEXT_RESOURCE + ", but may have one only: "
                    + resources.stream().map(URL::toString).collect(Collectors.joining(", ")));
        }
        return resources.isEmpty() ? null : resources.get(0);
    }

    /** The class loader's own resources of this name, which its parent does not see. */
    private static List<URL> ownResources(ClassLoader loader, String name) {
        try {
            List<URL> resources = Collections.list(loader.getResources(name));
            if (resources.isEmpty()) {
                return List.of();
            }
            ClassLoader parent = loader.getParent();
            Set<String> inherited = new HashSet<>();
            if (parent != null) {
                for (URL resource : Collections.list(parent.getResources(name))) {
                    inherited.add(resource.toString());
                }
            }
            List<URL> own = new ArrayList<>();
            for (URL resource : resources) {
                if (!inherited.contains(resource.toString())) {
                    own.add(resource);
                }
            }
            return own;
        } catch (IOException | RuntimeException e) {
            Log.debug(() -> "cannot look for " + name + " in " + describe(loader) + ": " + e);
            return List.of();
        }
    }

    /** The jar or directory of a configuration file: its URL without the file name, see {@link #source(String)}. */
    static String source(URL resource) {
        String url = resource.toString();
        return source(url.substring(0, url.length() - RESOURCE.length()));
    }

    /**
     * The jar or directory a class comes from: the location of its code source, or else where its class loader finds its
     * class file; null if neither is known, e.g. for a class generated at runtime.
     */
    static String source(ClassLoader loader, String className, ProtectionDomain protectionDomain) {
        CodeSource codeSource = protectionDomain != null ? protectionDomain.getCodeSource() : null;
        URL location = codeSource != null ? codeSource.getLocation() : null;
        if (location != null) {
            return source(location.toString());
        }
        String path = className.replace('.', '/') + ".class";
        try {
            URL classFile = loader.getResource(path);
            String url = classFile != null ? classFile.toString() : null;
            return url != null && url.endsWith(path) ? source(url.substring(0, url.length() - path.length())) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A jar's or directory's URL in one form: {@code jar:file:/app/a.jar!/} and {@code file:/app/a.jar} as
     * {@code file:/app/a.jar}, {@code file:///app/classes/} as {@code file:/app/classes}. Code sources name jars without
     * {@code jar:}, resources in them with it; nested jars, e.g. Spring Boot's
     * {@code jar:nested:/app.jar/!BOOT-INF/classes/!/}, keep their inner {@code !}. A file's URL is encoded one way, with
     * {@code /} as separator, as URLs may name the same file differently: on Windows, Quarkus's code sources are e.g.
     * {@code file:/C:%5CUsers%5Capp.jar}, with encoded backslashes, its resources {@code jar:file:/C:/Users/app.jar!/...}.
     */
    static String source(String url) {
        String source = url;
        if (source.startsWith("jar:")) {
            source = source.substring("jar:".length());
        }
        if (source.endsWith("!/")) {
            source = source.substring(0, source.length() - "!/".length());
        }
        if (source.startsWith("file:") && source.indexOf('!') < 0) {
            try {
                String path = new URI(source).getPath().replace(File.separatorChar, '/');
                source = new URI("file", null, path, null).toString();
            } catch (URISyntaxException | RuntimeException e) {
                // Not a valid URI: compared as it is.
            }
        }
        if (source.startsWith("file:///")) {
            source = "file:/" + source.substring("file:///".length());
        }
        while (source.endsWith("/")) {
            source = source.substring(0, source.length() - 1);
        }
        return source;
    }

    /** The class loader's name, or its class, with its identity. */
    static String describe(ClassLoader loader) {
        String name = loader.getName();
        return (name != null ? name : loader.getClass().getName()) + "@" + Integer.toHexString(System.identityHashCode(loader));
    }
}
