package com.hafnium.stackaugmentor.bridge;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * A concurrent map with weakly referenced keys compared by identity.
 * {@link java.util.WeakHashMap} is not usable for throwables, because it relies on {@code equals}.
 */
public final class WeakIdentityMap<K, V> {

    private final ConcurrentHashMap<Key<K>, V> map = new ConcurrentHashMap<>();
    private final ReferenceQueue<K> queue = new ReferenceQueue<>();

    public V get(K key) {
        expunge();
        return map.get(new Key<>(key, null));
    }

    public void put(K key, V value) {
        expunge();
        map.put(new Key<>(key, queue), value);
    }

    public V compute(K key, BiFunction<K, V, V> function) {
        expunge();
        return map.compute(new Key<>(key, queue), (k, v) -> function.apply(key, v));
    }

    public int size() {
        expunge();
        return map.size();
    }

    private void expunge() {
        Reference<? extends K> reference;
        while ((reference = queue.poll()) != null) {
            map.remove(reference);
        }
    }

    private static final class Key<K> extends WeakReference<K> {

        private final int hash;

        Key(K referent, ReferenceQueue<K> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key<?> key)) {
                return false;
            }
            Object referent = get();
            return referent != null && referent == key.get();
        }
    }
}
