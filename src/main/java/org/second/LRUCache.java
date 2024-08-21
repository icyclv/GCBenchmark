package org.second;

import java.util.LinkedHashMap;
import java.util.Map;

public class LRUCache<Key, Value> {
    private final int size;
    private final LinkedHashMap<Key, Value> cache;

    public LRUCache(int size) {
        this.size = size;
        this.cache = new LinkedHashMap<>(size * 4 / 3, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Value> eldest) {
                return size() > size;
            }
        };
    }

    public Value get(Key key) {
        return cache.get(key);
    }

    public void put(Key key, Value value) {
        cache.put(key, value);
    }

    public int size() {
        return cache.size();
    }


}
