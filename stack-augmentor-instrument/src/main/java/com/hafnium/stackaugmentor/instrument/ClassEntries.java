package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.runtime.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.IdSpec;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;

import java.util.function.Predicate;

/**
 * Finds the deciding {@code [augment.classes]} entry of a type: that of the first class up the superclass chain
 * that an entry matches.
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

    /** The deciding entry, or {@code null} if no entry matches the type or any of its superclasses. */
    Deciding decide(TypeDescription type) {
        Cached last = cached;
        if (last != null && last.type() == type) {
            return last.deciding();
        }
        Deciding[] found = new Deciding[1];
        firstInHierarchy(type, it -> {
            AugmentorConfig.ClassEntry entry = config.classEntry(it.getName());
            if (entry != null) {
                found[0] = new Deciding(it, entry);
                return true;
            }
            return false;
        });
        cached = new Cached(type, found[0]);
        return found[0];
    }

    /** Whether the type's {@code @StackTraceId} is used: its deciding entry is {@code "@"}. */
    boolean annotationsUsed(TypeDescription type) {
        Deciding deciding = decide(type);
        return deciding != null && deciding.annotations();
    }

    /**
     * The first of the type and its superclasses that matches, stopping at {@code Object} or at a superclass that
     * cannot be resolved. Superclasses are only resolved as far as needed.
     */
    static TypeDescription firstInHierarchy(TypeDescription type, Predicate<TypeDescription> predicate) {
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
}
