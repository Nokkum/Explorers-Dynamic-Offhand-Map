package com.explorermap.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryUploadTrackerTest {

    private long nowNanos;
    private DiscoveryUploadTracker<String> tracker;

    @BeforeEach
    void setUp() {
        nowNanos = 1_000_000_000L;
        tracker = new DiscoveryUploadTracker<>(() -> nowNanos);
    }

    private void advanceMillis(long millis) {
        nowNanos += millis * 1_000_000L;
    }

    @Test
    void nothingToUploadBeforeAnyDiscovery() {
        assertFalse(tracker.needsUpload("map", 0L));
    }

    @Test
    void newGenerationNeedsUploadUntilSent() {
        assertTrue(tracker.needsUpload("map", 1L));
        tracker.markSent("map", 1L);
        assertFalse(tracker.needsUpload("map", 1L));
    }

    @Test
    void unacknowledgedUploadIsRetriedAfterTheTimeout() {
        tracker.markSent("map", 1L);
        advanceMillis(DiscoveryUploadTracker.ACK_TIMEOUT_MILLIS - 1);
        assertFalse(tracker.needsUpload("map", 1L));
        advanceMillis(1);
        assertTrue(tracker.needsUpload("map", 1L));
    }

    @Test
    void retriesBackOffAndStopAfterTheMaximumAttempts() {
        long wait = DiscoveryUploadTracker.ACK_TIMEOUT_MILLIS;
        for (int attempt = 1; attempt < DiscoveryUploadTracker.MAX_ATTEMPTS; attempt++) {
            tracker.markSent("map", 1L);
            advanceMillis(wait);
            assertTrue(tracker.needsUpload("map", 1L), "attempt " + attempt);
            wait *= 2;
        }
        tracker.markSent("map", 1L);
        advanceMillis(wait * 4);
        assertFalse(tracker.needsUpload("map", 1L));
    }

    @Test
    void acknowledgementStopsRetriesUntilTheNextGeneration() {
        tracker.markSent("map", 1L);
        tracker.acknowledge("map", 1L);
        advanceMillis(60_000L);
        assertFalse(tracker.needsUpload("map", 1L));
        assertFalse(tracker.hasUnacknowledged("map", 1L));

        assertTrue(tracker.needsUpload("map", 2L));
        assertTrue(tracker.hasUnacknowledged("map", 2L));
    }

    @Test
    void mapsAreTrackedIndependently() {
        tracker.markSent("a", 1L);
        assertFalse(tracker.needsUpload("a", 1L));
        assertTrue(tracker.needsUpload("b", 1L));
    }

    @Test
    void clearForgetsEverything() {
        tracker.markSent("map", 1L);
        tracker.acknowledge("map", 1L);
        tracker.clear();
        assertTrue(tracker.needsUpload("map", 1L));
    }
}
