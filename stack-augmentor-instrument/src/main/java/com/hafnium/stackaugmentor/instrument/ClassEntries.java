package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.ReceiverSpec;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds the deciding {@code [augment.receiver]} entry of a method: the first entry that matches its type, a pattern by
 * the type's own name, a class name by the type's own name or, with {@code +}, also as a superclass or an implemented
 * interface, and applies to the method
 * ({@link AugmentorConfig#classEntry(Class, String)}).
 */
final class ClassEntries {

    /** The deciding entry, and the class it matched. */
    record Deciding(TypeDescription owner, AugmentorConfig.ClassEntry entry) {

        /** {@code "-"}: no receiver members. */
        boolean excluded() {
            return entry.spec() instanceof ReceiverSpec.Excluded;
        }
    }

    private record Cached(TypeDescription type, List<AugmentorConfig.ClassEntry> entries) {
    }

    private final AugmentorConfig config;

    /** The last answer: it is asked for several times per type while the type is transformed. */
    private volatile Cached cached;

    ClassEntries(AugmentorConfig config) {
        this.config = config;
    }

    /** The entries whose class part matches the type, in the order of the file. */
    List<AugmentorConfig.ClassEntry> entries(TypeDescription type) {
        Cached last = cached;
        if (last != null && last.type() == type) {
            return last.entries();
        }
        List<AugmentorConfig.ClassEntry> entries = config.classEntries(type.getName(), new SupertypeNames(type)::contains);
        cached = new Cached(type, entries);
        return entries;
    }

    /** The deciding entry of the method's frames, or {@code null} if no entry matches the type and the method. */
    Deciding decide(TypeDescription type, MethodDescription method) {
        AugmentorConfig.ClassEntry entry = AugmentorConfig.firstApplying(entries(type), method.getInternalName());
        return entry != null ? new Deciding(type, entry) : null;
    }

    /**
     * The names of a type, its superclasses and the interfaces they implement, directly or not: collected when first
     * asked for, as only a key with {@code +} needs them. A supertype that cannot be resolved is left out, with
     * its own supertypes.
     */
    static final class SupertypeNames {

        private final TypeDescription type;
        /** Published only once complete: the agent and the live-stack mode ask from several threads. */
        private volatile Set<String> names;

        SupertypeNames(TypeDescription type) {
            this.type = type;
        }

        TypeDescription type() {
            return type;
        }

        boolean contains(String name) {
            Set<String> known = names;
            if (known == null) {
                known = new HashSet<>();
                collect(type, known);
                names = known;
            }
            return known.contains(name);
        }

        private static void collect(TypeDefinition current, Set<String> names) {
            List<TypeDefinition> supertypes = new ArrayList<>();
            try {
                TypeDescription erasure = current.asErasure();
                if (!names.add(erasure.getName())) {
                    return;
                }
                if (erasure.getSuperClass() != null) {
                    supertypes.add(erasure.getSuperClass());
                }
                supertypes.addAll(erasure.getInterfaces());
            } catch (RuntimeException e) {
                // a type that cannot be resolved: its supertypes are left out
            }
            for (TypeDefinition supertype : supertypes) {
                collect(supertype, names);
            }
        }
    }

    /**
     * The first of the type and its superclasses that matches, stopping at {@code Object} or at a superclass that
     * cannot be resolved. Superclasses are only resolved as far as needed. For an interface: the first of the
     * interface and the interfaces it extends, directly or not, nearest first.
     */
    static TypeDescription firstInHierarchy(TypeDescription type, Predicate<TypeDescription> predicate) {
        if (type.isInterface()) {
            return firstInInterfaces(type, predicate);
        }
        TypeDefinition current = type;
        while (current != null) {
            TypeDescription erasure = current.asErasure();
            if (erasure.represents(Object.class)) {
                return null;
            }
            if (predicate.test(erasure)) {
                return erasure;
            }
            try {
                current = current.getSuperClass();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static TypeDescription firstInInterfaces(TypeDescription type, Predicate<TypeDescription> predicate) {
        Deque<TypeDescription> queue = new ArrayDeque<>(List.of(type));
        Set<TypeDescription> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            TypeDescription current = queue.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            if (predicate.test(current)) {
                return current;
            }
            try {
                for (TypeDefinition superInterface : current.getInterfaces()) {
                    queue.addLast(superInterface.asErasure());
                }
            } catch (RuntimeException e) {
                // an interface that cannot be resolved: its superinterfaces are skipped
            }
        }
        return null;
    }
}
