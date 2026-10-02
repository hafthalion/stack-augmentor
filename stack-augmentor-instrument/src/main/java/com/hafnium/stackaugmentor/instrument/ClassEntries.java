package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.IdSpec;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds the deciding {@code [augment.receiver]} entry of a type: the most specific entry that matches the type's own
 * name. Entries of superclasses do not apply.
 */
final class ClassEntries {

    /** The deciding entry, and the class it matched. */
    record Deciding(TypeDescription owner, AugmentorConfig.ClassEntry entry) {

        /** {@code "@"}: the class's {@code @StackTraceId} is used. */
        boolean annotations() {
            return entry.spec() instanceof IdSpec.Annotations;
        }

        /** {@code "-"}: no receiver id. */
        boolean excluded() {
            return entry.spec() instanceof IdSpec.Excluded;
        }
    }

    private record Cached(TypeDescription type, Deciding deciding) {
    }

    private final AugmentorConfig config;

    /** The last answer: it is asked for several times per type while the type is transformed. */
    private volatile Cached cached;

    ClassEntries(AugmentorConfig config) {
        this.config = config;
    }

    /** The deciding entry, or {@code null} if no entry matches the type. */
    Deciding decide(TypeDescription type) {
        Cached last = cached;
        if (last != null && last.type() == type) {
            return last.deciding();
        }
        AugmentorConfig.ClassEntry entry = config.classEntry(type.getName());
        Deciding deciding = entry != null ? new Deciding(type, entry) : null;
        cached = new Cached(type, deciding);
        return deciding;
    }

    /** Whether the type's {@code @StackTraceId} is used: its deciding entry is {@code "@"}. */
    boolean annotationsUsed(TypeDescription type) {
        Deciding deciding = decide(type);
        return deciding != null && deciding.annotations();
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
