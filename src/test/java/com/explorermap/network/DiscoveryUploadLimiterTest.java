package com.explorermap.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryUploadLimiterTest {

    private static final int UPLOADS_PER_WINDOW = 8;
    private static final long WINDOW_MILLIS = 3_000L;
    private static final int PAYLOAD_BYTES = 2048;
    private static final int BURST = UPLOADS_PER_WINDOW * DiscoveryUploadLimiter.BURST_MULTIPLIER;

    private long nowNanos;
    private DiscoveryUploadLimiter limiter;

    @BeforeEach
    void setUp() {
        nowNanos = 1_000_000_000L;
        limiter = new DiscoveryUploadLimiter(() -> nowNanos, UPLOADS_PER_WINDOW, WINDOW_MILLIS, PAYLOAD_BYTES);
    }

    private void advanceMillis(long millis) {
        nowNanos += millis * 1_000_000L;
    }

    @Test
    void allowsABurstThenRejects() {
        UUID player = UUID.randomUUID();
        for (int i = 0; i < BURST; i++) assertTrue(limiter.tryAcquire(player, PAYLOAD_BYTES), "upload " + i);
        assertFalse(limiter.tryAcquire(player, PAYLOAD_BYTES));
    }

    @Test
    void refillsOverTime() {
        UUID player = UUID.randomUUID();
        for (int i = 0; i < BURST; i++) limiter.tryAcquire(player, PAYLOAD_BYTES);
        assertFalse(limiter.tryAcquire(player, PAYLOAD_BYTES));

        advanceMillis(WINDOW_MILLIS / UPLOADS_PER_WINDOW + 25);
        assertTrue(limiter.tryAcquire(player, PAYLOAD_BYTES));
        assertFalse(limiter.tryAcquire(player, PAYLOAD_BYTES));
    }

    @Test
    void playersAreLimitedIndependently() {
        UUID first = UUID.randomUUID();
        for (int i = 0; i < BURST; i++) limiter.tryAcquire(first, PAYLOAD_BYTES);
        assertFalse(limiter.tryAcquire(first, PAYLOAD_BYTES));
        assertTrue(limiter.tryAcquire(UUID.randomUUID(), PAYLOAD_BYTES));
    }

    @Test
    void globalBudgetCapsAllPlayersTogether() {
        int granted = 0;
        for (int i = 0; i < 200; i++) {
            if (limiter.tryAcquire(UUID.randomUUID(), PAYLOAD_BYTES)) granted++;
        }
        assertTrue(granted == (int) DiscoveryUploadLimiter.GLOBAL_BURST, "granted " + granted);
    }

    @Test
    void rejectsNullPlayerAndNegativeSize() {
        assertFalse(limiter.tryAcquire(null, PAYLOAD_BYTES));
        assertFalse(limiter.tryAcquire(UUID.randomUUID(), -1));
    }

    @Test
    void repeatedInvalidPacketsSuspendThePlayerUntilTheCooldownEnds() {
        UUID player = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(player, PAYLOAD_BYTES));

        int strikes = (int) DiscoveryUploadLimiter.STRIKE_LIMIT;
        for (int i = 0; i < strikes - 1; i++) assertFalse(limiter.recordInvalid(player), "strike " + i);
        assertTrue(limiter.recordInvalid(player));
        assertFalse(limiter.tryAcquire(player, PAYLOAD_BYTES));

        advanceMillis(DiscoveryUploadLimiter.COOLDOWN_MILLIS - 1);
        assertFalse(limiter.tryAcquire(player, PAYLOAD_BYTES));
        advanceMillis(2);
        assertTrue(limiter.tryAcquire(player, PAYLOAD_BYTES));
    }

    @Test
    void strikesDecayWhenThePlayerBehaves() {
        UUID player = UUID.randomUUID();
        limiter.tryAcquire(player, PAYLOAD_BYTES);
        for (int i = 0; i < 20; i++) limiter.recordInvalid(player);

        advanceMillis(60_000L);
        for (int i = 0; i < 20; i++) assertFalse(limiter.recordInvalid(player));
    }

    @Test
    void invalidPacketsFromAnUnknownPlayerAreIgnored() {
        assertFalse(limiter.recordInvalid(UUID.randomUUID()));
        assertFalse(limiter.recordInvalid(null));
    }

    @Test
    void forgettingAPlayerResetsTheirBudget() {
        UUID player = UUID.randomUUID();
        for (int i = 0; i < BURST; i++) limiter.tryAcquire(player, PAYLOAD_BYTES);
        assertFalse(limiter.tryAcquire(player, PAYLOAD_BYTES));

        limiter.forget(player);
        assertTrue(limiter.tryAcquire(player, PAYLOAD_BYTES));
    }
}
