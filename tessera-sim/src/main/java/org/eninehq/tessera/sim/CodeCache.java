package org.eninehq.tessera.sim;

import java.lang.ref.SoftReference;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generated simulator code kept for reuse, keyed by its exact content. At most {@link #MAX}
 * entries (least recently used dropped first), each held softly, so a long-running process that builds
 * many different designs (a design server, mutation runs) does not keep all their classes and keys.
 */
final class CodeCache<K, V> {
    static final int MAX = 64;

    private final LinkedHashMap<K, SoftReference<V>> map = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<K, SoftReference<V>> e) {
            return size() > MAX;
        }
    };

    synchronized V get(K key) {
        var ref = map.get(key);
        V v = ref == null ? null : ref.get();
        if (ref != null && v == null) map.remove(key);
        return v;
    }

    synchronized void put(K key, V value) {
        map.values().removeIf(r -> r.get() == null);
        map.put(key, new SoftReference<>(value));
    }

    synchronized int size() { return map.size(); }
}
