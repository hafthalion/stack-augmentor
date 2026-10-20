package com.hafnium.stackaugmentor.runtime.config;

import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.WeakIdentityMap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The configurations of one JVM by class loader, so that applications in one server, each with a class loader of its
 * own, can each have a configuration of their own. A class uses the {@value #RESOURCE} of the nearest class loader, from
 * its own up to the base class loaders, that has one of its own, one that its parent does not see; without one, the base
 * configuration applies: the agent's, or that of the runtime library's class loader. The base class loaders are the
 * system class loader, the class loader of the agent or the runtime library, and their parents.
 *
 * <p>A configuration is read once per class loader, when a class of it first needs it, and kept as long as the class
 * loader lives. An invalid one is printed as an error, and the classes of that class loader are not augmented.
 *
 * @param <T> what the component makes of a configuration: the values it needs, never classes of the class loader, which
 *            would keep it alive
 */
public final class ClassLoaderConfigs<T> {

    /** The configuration file of a class loader. */
    public static final String RESOURCE = "stack-augmentor.toml";

    private final String component;
    private final T base;
    private final Function<AugmentorConfig, T> factory;
    /** Null where every class uses the base configuration. */
    private final Set<ClassLoader> baseLoaders;

    /** By class loader: its configuration, empty where it is invalid. */
    private final WeakIdentityMap<ClassLoader, Optional<T>> byLoader = new WeakIdentityMap<>();

    /** The class loaders whose configuration this thread is reading: one that it asks for again gets none. */
    private final ThreadLocal<List<ClassLoader>> reading = ThreadLocal.withInitial(ArrayList::new);

    private ClassLoaderConfigs(String component, T base, Function<AugmentorConfig, T> factory, Set<ClassLoader> baseLoaders) {
        this.component = component;
        this.base = base;
        this.factory = factory;
        this.baseLoaders = baseLoaders;
    }

    /**
     * @param component   the component, for the messages
     * @param base        made of the base configuration
     * @param factory     makes what the component needs of a class loader's configuration
     * @param baseLoaders class loaders whose classes use the base configuration, with their parents and the system class
     *                    loader's
     */
    public static <T> ClassLoaderConfigs<T> perClassLoader(String component, T base, Function<AugmentorConfig, T> factory,
                                                           ClassLoader... baseLoaders) {
        Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ClassLoader> roots = new ArrayList<>(List.of(baseLoaders));
        roots.add(ClassLoader.getSystemClassLoader());
        for (ClassLoader root : roots) {
            for (ClassLoader loader = root; loader != null; loader = loader.getParent()) {
                loaders.add(loader);
            }
        }
        return new ClassLoaderConfigs<>(component, base, factory, loaders);
    }

    /** Every class uses {@code base}: for tests, and for the configurations given in code. */
    public static <T> ClassLoaderConfigs<T> fixed(T base) {
        return new ClassLoaderConfigs<>(null, base, null, null);
    }

    public T base() {
        return base;
    }

    /** What the class's configuration gives, or null if its class loader's configuration is invalid. */
    public T forClass(Class<?> type) {
        return forLoader(type.getClassLoader());
    }

    /**
     * What the configuration of the classes of this class loader gives, or null if it is invalid, or if it is being read
     * on this thread, e.g. because reading it loads a class.
     */
    public T forLoader(ClassLoader loader) {
        if (baseLoaders == null || loader == null || baseLoaders.contains(loader)) {
            return base;
        }
        Optional<T> known = byLoader.get(loader);
        if (known != null) {
            return known.orElse(null);
        }
        List<ClassLoader> current = reading.get();
        if (current.contains(loader)) {
            return null;
        }
        current.add(loader);
        try {
            URL own = ownResource(loader);
            Optional<T> found = own != null ? read(loader, own) : Optional.ofNullable(forLoader(loader.getParent()));
            return byLoader.setIfAbsent(loader, found).orElse(null);
        } finally {
            current.remove(loader);
        }
    }

    /** Whether the base configuration, or one of the class loaders read so far, matches. */
    public boolean anyMatches(Predicate<T> predicate) {
        if (predicate.test(base)) {
            return true;
        }
        if (baseLoaders == null) {
            return false;
        }
        for (Optional<T> value : byLoader.values()) {
            if (value.isPresent() && predicate.test(value.get())) {
                return true;
            }
        }
        return false;
    }

    /** The class loader's own {@value #RESOURCE}, which its parent does not see, or null. */
    private static URL ownResource(ClassLoader loader) {
        try {
            List<URL> resources = Collections.list(loader.getResources(RESOURCE));
            if (resources.isEmpty()) {
                return null;
            }
            ClassLoader parent = loader.getParent();
            Set<String> inherited = new HashSet<>();
            if (parent != null) {
                for (URL resource : Collections.list(parent.getResources(RESOURCE))) {
                    inherited.add(resource.toString());
                }
            }
            for (URL resource : resources) {
                if (!inherited.contains(resource.toString())) {
                    return resource;
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            Log.debug(() -> "cannot look for " + RESOURCE + " in " + describe(loader) + ": " + e);
            return null;
        }
    }

    private Optional<T> read(ClassLoader loader, URL resource) {
        String location = resource + " of class loader " + describe(loader);
        try {
            String text;
            try (InputStream in = resource.openStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            AugmentorConfig config = AugmentorConfig.parse(text, resource.toString());
            Startup.configureFor(component, location, config);
            return Optional.of(factory.apply(config));
        } catch (ConfigException e) {
            Log.error(component + ": " + e.getMessage() + "; the classes of class loader " + describe(loader) + " are not augmented");
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            Log.error(component + ": cannot read " + location + ", so its classes are not augmented: " + e);
            return Optional.empty();
        }
    }

    /** The class loader's name, or its class, with its identity. */
    static String describe(ClassLoader loader) {
        String name = loader.getName();
        return (name != null ? name : loader.getClass().getName()) + "@" + Integer.toHexString(System.identityHashCode(loader));
    }
}
