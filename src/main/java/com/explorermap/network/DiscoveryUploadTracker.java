package com.explorermap.network;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public final class DiscoveryUploadTracker<K> {

    public static final long ACK_TIMEOUT_MILLIS = 5_000L;
    public static final int MAX_ATTEMPTS = 4;

    private static final long ACK_TIMEOUT_NANOS = ACK_TIMEOUT_MILLIS * 1_000_000L;

    private record Pending(long generation, int attempts, long retryAt) {}

    private final LongSupplier clock;
    private final Map<K, Long> acknowledged = new HashMap<>();
    private final Map<K, Pending> pending = new HashMap<>();

    public DiscoveryUploadTracker(LongSupplier clock) {
        this.clock = clock;
    }

    public boolean needsUpload(K key, long generation) {
        if (generation == 0L || isAcknowledged(key, generation)) return false;
        Pending current = pending.get(key);
        if (current == null || current.generation() != generation) return true;
        return current.attempts() < MAX_ATTEMPTS && clock.getAsLong() - current.retryAt() >= 0L;
    }

    public void markSent(K key, long generation) {
        Pending previous = pending.get(key);
        int attempts = previous != null && previous.generation() == generation ? previous.attempts() + 1 : 1;
        long retryAt = clock.getAsLong() + (ACK_TIMEOUT_NANOS << (attempts - 1));
        pending.put(key, new Pending(generation, attempts, retryAt));
    }

    public void acknowledge(K key, long generation) {
        acknowledged.merge(key, generation, Math::max);
        Pending current = pending.get(key);
        if (current != null && current.generation() == generation) pending.remove(key);
    }

    public boolean hasUnacknowledged(K key, long generation) {
        return generation != 0L && !isAcknowledged(key, generation);
    }

    public void clear() {
        acknowledged.clear();
        pending.clear();
    }

    private boolean isAcknowledged(K key, long generation) {
        Long value = acknowledged.get(key);
        return value != null && value == generation;
    }
}
