package com.explorermap.mod.attachment;

import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.waypoint.Waypoint;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Attached to every MapState that has been observed through this mod.
 *
 * Stored fields
 * ─────────────
 * discoveredPixels  – 128×128 bitmask (2 048 bytes) marking which pixels
 *                     the player has actually seen via FOV ray-cast.
 *                     Bit index = row * 128 + col.
 *
 * expansions        – List of unlocked directional tiles. Each record holds
 *                     the vanilla map ID of the adjacent tile plus which
 *                     direction it was unlocked in.
 *
 * waypoints         – Named, positioned markers placed by the player.
 *
 * All fields are serialized via Codec so Fabric's persistent attachment
 * system writes them to level NBT automatically.
 */
public class MapDiscoveryAttachment {

    // ── Constants ─────────────────────────────────────────────────────────
    public static final int MAP_SIZE = 128;
    public static final int PIXEL_COUNT = MAP_SIZE * MAP_SIZE; // 16 384
    public static final int BYTE_COUNT  = PIXEL_COUNT / 8;     //  2 048

    // ── Fields ────────────────────────────────────────────────────────────
    /** Raw bitmask. Index 0 = top-left pixel. */
    private final byte[] discoveredPixels;

    private final List<ExpansionRecord> expansions;
    private final List<Waypoint>        waypoints;

    /**
     * Monotonically increasing counter incremented every time a new pixel
     * is discovered. Used by TileTextureCache to decide whether to rebuild
     * the GPU texture. Transient — not serialized (rebuilt on first access).
     */
    private volatile long discoveryGeneration = 0L;

    // ── Constructors ──────────────────────────────────────────────────────

    /** Default: nothing discovered yet. */
    public MapDiscoveryAttachment() {
        this.discoveredPixels = new byte[BYTE_COUNT];
        this.expansions       = new ArrayList<>();
        this.waypoints        = new ArrayList<>();
    }

    /** Deserialization constructor. */
    public MapDiscoveryAttachment(byte[] discoveredPixels,
                                  List<ExpansionRecord> expansions,
                                  List<Waypoint> waypoints) {
        // Defensive copy + size guard
        this.discoveredPixels = discoveredPixels.length == BYTE_COUNT
                ? discoveredPixels.clone()
                : new byte[BYTE_COUNT];
        this.expansions = new ArrayList<>(expansions);
        this.waypoints  = new ArrayList<>(waypoints);
    }

    // ── Bitmask API ───────────────────────────────────────────────────────

    /**
     * Marks pixel (col, row) as discovered. Thread-safe via synchronized.
     * col and row are clamped to [0, 127].
     */
    public synchronized void discover(int col, int row) {
        if (col < 0 || col >= MAP_SIZE || row < 0 || row >= MAP_SIZE) return;
        int bit   = row * MAP_SIZE + col;
        int index = bit >> 3;
        int shift = bit & 7;
        byte before = discoveredPixels[index];
        discoveredPixels[index] |= (byte) (1 << shift);
        // Increment generation only when a new pixel is actually revealed
        if (discoveredPixels[index] != before) discoveryGeneration++;
    }

    /** Returns the current discovery generation counter (for cache dirty-tracking). */
    public long getDiscoveryGeneration() { return discoveryGeneration; }

    /** Returns true if pixel (col, row) has been discovered. */
    public boolean isDiscovered(int col, int row) {
        if (col < 0 || col >= MAP_SIZE || row < 0 || row >= MAP_SIZE) return false;
        int bit   = row * MAP_SIZE + col;
        int index = bit >> 3;
        int shift = bit & 7;
        return (discoveredPixels[index] & (1 << shift)) != 0;
    }

    /** Returns a snapshot copy of the bitmask (safe for rendering). */
    public byte[] getDiscoveredPixelsCopy() {
        return discoveredPixels.clone();
    }

    /**
     * Returns the bitmask encoded as 256 longs for network transmission.
     * Same encoding as BITMASK_CODEC — little-endian, 8 bytes per long.
     * Synchronized to prevent a race with concurrent discover() calls.
     */
    public synchronized java.util.List<Long> getDiscoveryLongs() {
        java.util.List<Long> longs = new java.util.ArrayList<>(BYTE_COUNT / 8);
        for (int i = 0; i < BYTE_COUNT / 8; i++) {
            long v = 0;
            for (int b = 0; b < 8; b++) {
                v |= ((long)(discoveredPixels[i * 8 + b] & 0xFF)) << (b * 8);
            }
            longs.add(v);
        }
        return longs;
    }

    /**
     * OR-merges an incoming bitmask (encoded as longs) into this attachment.
     * Returns true if any new pixel was discovered (i.e., the bitmask changed).
     * Thread-safe.
     */
    public synchronized boolean mergeDiscoveryLongs(java.util.List<Long> longs) {
        boolean changed = false;
        int n = Math.min(longs.size(), BYTE_COUNT / 8);
        for (int i = 0; i < n; i++) {
            long v = longs.get(i);
            for (int b = 0; b < 8; b++) {
                int idx = i * 8 + b;
                byte before = discoveredPixels[idx];
                discoveredPixels[idx] |= (byte)(v >> (b * 8));
                if (discoveredPixels[idx] != before) {
                    discoveryGeneration++;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Progress: fraction of the 128×128 grid that has been discovered. */
    public float discoveryFraction() {
        int count = 0;
        for (byte b : discoveredPixels) count += Integer.bitCount(b & 0xFF);
        return (float) count / PIXEL_COUNT;
    }

    // ── Expansion API ─────────────────────────────────────────────────────

    public List<ExpansionRecord> getExpansions() { return expansions; }

    public void addExpansion(ExpansionRecord record) {
        expansions.add(record);
    }

    public boolean hasExpansion(ExpansionRecord.Direction dir) {
        return expansions.stream().anyMatch(r -> r.direction() == dir);
    }

    // ── Waypoint API ──────────────────────────────────────────────────────

    public List<Waypoint> getWaypoints() { return waypoints; }

    public void addWaypoint(Waypoint wp) {
        waypoints.add(wp);
    }

    public void removeWaypoint(String name) {
        waypoints.removeIf(wp -> wp.name().equals(name));
    }

    /**
     * Replaces the entire waypoint list atomically.
     * Used by SyncWaypointsPayload to apply the server-authoritative list.
     * Synchronized on the attachment so concurrent render reads get a consistent snapshot.
     */
    public synchronized void syncWaypoints(java.util.List<Waypoint> incoming) {
        waypoints.clear();
        waypoints.addAll(incoming);
    }

    // ── Codec ─────────────────────────────────────────────────────────────

    /**
     * Encodes the 2048-byte discovery bitmask as 256 longs (little-endian, 8 bytes per long).
     * Using Codec.LONG avoids Codec.BYTE_BUFFER which is not part of DFU's public API.
     */
    private static final Codec<byte[]> BITMASK_CODEC = Codec.LONG.listOf().xmap(
            longs -> {
                byte[] arr = new byte[BYTE_COUNT];
                int n = Math.min(longs.size(), BYTE_COUNT / 8);
                for (int i = 0; i < n; i++) {
                    long v = longs.get(i);
                    for (int b = 0; b < 8; b++) {
                        arr[i * 8 + b] = (byte)(v >> (b * 8));
                    }
                }
                return arr;
            },
            arr -> {
                java.util.List<Long> longs = new java.util.ArrayList<>(BYTE_COUNT / 8);
                for (int i = 0; i < BYTE_COUNT / 8; i++) {
                    long v = 0;
                    for (int b = 0; b < 8; b++) {
                        v |= ((long)(arr[i * 8 + b] & 0xFF)) << (b * 8);
                    }
                    longs.add(v);
                }
                return longs;
            }
    );

    public static final Codec<MapDiscoveryAttachment> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BITMASK_CODEC.fieldOf("discovered_pixels").forGetter(a -> a.discoveredPixels),

                    ExpansionRecord.CODEC.listOf().fieldOf("expansions")
                            .forGetter(a -> a.expansions),

                    Waypoint.CODEC.listOf().fieldOf("waypoints")
                            .forGetter(a -> a.waypoints)

            ).apply(instance, MapDiscoveryAttachment::new)
    );
}
