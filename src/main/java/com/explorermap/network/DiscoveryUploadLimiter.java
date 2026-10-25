package com.explorermap.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class DiscoveryUploadLimiter {

    public static final int BURST_MULTIPLIER = 2;
    public static final double STRIKE_LIMIT = 32.0;
    public static final double STRIKE_DECAY_PER_SECOND = 1.0;
    public static final long COOLDOWN_MILLIS = 30_000L;
    public static final double GLOBAL_UPLOADS_PER_SECOND = 400.0;
    public static final double GLOBAL_BURST = 128.0;

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final long COOLDOWN_NANOS = COOLDOWN_MILLIS * 1_000_000L;

    private static final class PlayerState {
        double uploadTokens;
        double byteTokens;
        double strikes;
        long refilledAt;
        long cooldownUntil;
        boolean coolingDown;

        PlayerState(long now, double uploadTokens, double byteTokens) {
            this.uploadTokens = uploadTokens;
            this.byteTokens = byteTokens;
            this.refilledAt = now;
        }
    }

    private final LongSupplier clock;
    private final double uploadRatePerNano;
    private final double byteRatePerNano;
    private final double uploadBurst;
    private final double byteBurst;
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final Object globalLock = new Object();
    private double globalTokens = GLOBAL_BURST;
    private long globalRefilledAt;

    public DiscoveryUploadLimiter(LongSupplier clock, int uploadsPerWindow, long windowMillis, int maxPayloadBytes) {
        if (uploadsPerWindow <= 0 || windowMillis <= 0L || maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("Limiter parameters must be positive");
        }
        this.clock = clock;
        double windowNanos = windowMillis * 1_000_000.0;
        this.uploadRatePerNano = uploadsPerWindow / windowNanos;
        this.byteRatePerNano = (double) uploadsPerWindow * maxPayloadBytes / windowNanos;
        this.uploadBurst = (double) uploadsPerWindow * BURST_MULTIPLIER;
        this.byteBurst = uploadBurst * maxPayloadBytes;
        this.globalRefilledAt = clock.getAsLong();
    }

    public boolean tryAcquire(UUID playerId, int payloadBytes) {
        if (playerId == null || payloadBytes < 0) return false;
        long now = clock.getAsLong();
        PlayerState state = players.computeIfAbsent(playerId,
                id -> new PlayerState(now, uploadBurst, byteBurst));
        synchronized (state) {
            if (state.coolingDown) {
                if (now - state.cooldownUntil < 0L) return false;
                state.coolingDown = false;
                state.strikes = 0.0;
            }
            refill(state, now);
            if (state.uploadTokens < 1.0 || state.byteTokens < payloadBytes) return false;
            if (!takeGlobal(now)) return false;
            state.uploadTokens -= 1.0;
            state.byteTokens -= payloadBytes;
            return true;
        }
    }

    public boolean recordInvalid(UUID playerId) {
        if (playerId == null) return false;
        PlayerState state = players.get(playerId);
        if (state == null) return false;
        long now = clock.getAsLong();
        synchronized (state) {
            if (state.coolingDown) return false;
            refill(state, now);
            state.strikes += 1.0;
            if (state.strikes < STRIKE_LIMIT) return false;
            state.strikes = 0.0;
            state.coolingDown = true;
            state.cooldownUntil = now + COOLDOWN_NANOS;
            return true;
        }
    }

    public void forget(UUID playerId) {
        if (playerId != null) players.remove(playerId);
    }

    public void clear() {
        players.clear();
    }

    public int trackedPlayers() {
        return players.size();
    }

    private void refill(PlayerState state, long now) {
        long elapsed = Math.max(0L, now - state.refilledAt);
        state.refilledAt = now;
        state.uploadTokens = Math.min(uploadBurst, state.uploadTokens + elapsed * uploadRatePerNano);
        state.byteTokens = Math.min(byteBurst, state.byteTokens + elapsed * byteRatePerNano);
        state.strikes = Math.max(0.0, state.strikes - elapsed * STRIKE_DECAY_PER_SECOND / NANOS_PER_SECOND);
    }

    private boolean takeGlobal(long now) {
        synchronized (globalLock) {
            long elapsed = Math.max(0L, now - globalRefilledAt);
            globalRefilledAt = now;
            globalTokens = Math.min(GLOBAL_BURST,
                    globalTokens + elapsed * GLOBAL_UPLOADS_PER_SECOND / NANOS_PER_SECOND);
            if (globalTokens < 1.0) return false;
            globalTokens -= 1.0;
            return true;
        }
    }
}
