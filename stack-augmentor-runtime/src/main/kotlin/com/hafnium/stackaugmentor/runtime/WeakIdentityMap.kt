package com.hafnium.stackaugmentor.runtime

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * A concurrent map with weakly referenced keys compared by identity.
 * [java.util.WeakHashMap] is not usable for throwables, because it relies on `equals`.
 */
internal class WeakIdentityMap<K : Any, V : Any> {

    private val map = ConcurrentHashMap<Key<K>, V>()
    private val queue = ReferenceQueue<K>()

    operator fun get(key: K): V? {
        expunge()
        return map[Key(key, null)]
    }

    operator fun set(key: K, value: V) {
        expunge()
        map[Key(key, queue)] = value
    }

    private fun expunge() {
        while (true) {
            val reference = queue.poll() ?: return
            map.remove(reference)
        }
    }

    private class Key<K : Any>(referent: K, queue: ReferenceQueue<K>?) : WeakReference<K>(referent, queue) {

        private val hash = System.identityHashCode(referent)

        override fun hashCode() = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key<*>) return false
            val referent = get()
            return referent != null && referent === other.get()
        }
    }
}
