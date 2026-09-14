package com.explorermap.mod.data;

import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.waypoint.Waypoint;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-map exploration data: the fog-of-discovery bitmask, directional
 * expansion records, and waypoints for a single vanilla map.
 *
 * This replaces the old MapDiscoveryAttachment design, which incorrectly
 * tried to attach itself to MapState via Fabric's Data Attachment API —
 * an API that only targets Entity, BlockEntity, ServerWorld, and Chunk,
 * never PersistentState/MapState.
 *
 * Instead, every MapEntryData lives inside ExplorerMapSavedData, a proper
 * mod-owned PersistentState keyed by the map's raw integer ID (the `id`
 * component of its MapIdComponent). ExplorerMapSavedData owns dirty-marking:
 * any mutation method here should be followed by the caller invoking
 * ExplorerMapSavedData.markDirty() (see that class for the wrapper accessors
 * that do this automatically).
 *
 * This class itself holds no reference to its owning SavedData or to any
 * MapState/World — it is a plain, serializable data holder.
 */
public final class MapEntryData {

    public static final int MAP_SIZE    = 128;
    public static final int PIXEL_COUNT = MAP_SIZE * MAP_SIZE;   // 16 384
    public static final int BYTE_COUNT  = PIXEL_COUNT / 8;       //  2 048

    /** Raw discovery bitmask. Bit (row*128+col) = pixel (col,row) discovered. */
    private final byte[] discoveredPixels;

    private final List<ExpansionRecord> expansions;
    private final List<Waypoint> waypoints;

    /**
     * In-memory-only counter incremented whenever discover() actually flips
     * a new bit. Used by the client's texture cache to know when to rebuild
     * a tile's GPU texture. Not persisted — resets to 0 on load, which is
     * harmless since it only gates a redundant-rebuild optimization.
     */
    private transient long discoveryGeneration = 0L;

    public MapEntryData() {
        this.discoveredPixels = new byte[BYTE_COUNT];
        this.expansions = new ArrayList<>();
        this.waypoints = new ArrayList<>();
    }

    private MapEntryData(byte[] discoveredPixels, List<ExpansionRecord> expansions, List<Waypoint> waypoints) {
        this.discoveredPixels = discoveredPixels.length == BYTE_COUNT
                ? discoveredPixels.clone() : new byte[BYTE_COUNT];
        this.expansions = new ArrayList<>(expansions);
        this.waypoints = new ArrayList<>(waypoints);
    }

    // ── Discovery bitmask ────────────────────────────────────────────────

    /** Marks pixel (col,row) discovered. Returns true if this changed anything. */
    public boolean discover(int col, int row) {
        if (col < 0 || col >= MAP_SIZE || row < 0 || row >= MAP_SIZE) return false;
        int bit = row * MAP_SIZE + col;
        int idx = bit >> 3, shift = bit & 7;
        byte before = discoveredPixels[idx];
        discoveredPixels[idx] |= (byte) (1 << shift);
        if (discoveredPixels[idx] != before) {
            discoveryGeneration++;
            return true;
        }
        return false;
    }

    public boolean isDiscovered(int col, int row) {
        if (col < 0 || col >= MAP_SIZE || row < 0 || row >= MAP_SIZE) return false;
        int bit = row * MAP_SIZE + col;
        return (discoveredPixels[bit >> 3] & (1 << (bit & 7))) != 0;
    }

    public byte[] getDiscoveredPixelsCopy() {
        return discoveredPixels.clone();
    }

    public float discoveryFraction() {
        int count = 0;
        for (byte b : discoveredPixels) count += Integer.bitCount(b & 0xFF);
        return (float) count / PIXEL_COUNT;
    }

    public long getDiscoveryGeneration() {
        return discoveryGeneration;
    }

    /**
     * OR-merges an externally supplied bitmask (exactly BYTE_COUNT bytes
     * expected; shorter arrays are treated as zero-padded, anything longer
     * is truncated by the caller before this is invoked). Returns true if
     * any new pixel was revealed.
     */
    public boolean mergeBitmask(byte[] other) {
        boolean changed = false;
        int n = Math.min(other.length, BYTE_COUNT);
        for (int i = 0; i < n; i++) {
            byte before = discoveredPixels[i];
            discoveredPixels[i] |= other[i];
            if (discoveredPixels[i] != before) {
                discoveryGeneration++;
                changed = true;
            }
        }
        return changed;
    }

    // ── Expansions ───────────────────────────────────────────────────────

    /** Returns an unmodifiable snapshot — callers must go through addExpansion() to mutate. */
    public List<ExpansionRecord> getExpansions() {
        return List.copyOf(expansions);
    }

    public void addExpansion(ExpansionRecord record) {
        expansions.add(record);
    }

    public boolean hasExpansion(ExpansionRecord.Direction dir) {
        return expansions.stream().anyMatch(r -> r.direction() == dir);
    }

    // ── Waypoints ────────────────────────────────────────────────────────

    /** Returns an unmodifiable snapshot — callers must go through the mutation methods below. */
    public List<Waypoint> getWaypoints() {
        return List.copyOf(waypoints);
    }

    public void addWaypoint(Waypoint wp) {
        waypoints.add(wp);
    }

    public void removeWaypoint(String name) {
        waypoints.removeIf(wp -> wp.name().equals(name));
    }

    public void replaceAllWaypoints(List<Waypoint> newList) {
        waypoints.clear();
        waypoints.addAll(newList);
    }

    // ── Codec ────────────────────────────────────────────────────────────

    private static final Codec<byte[]> BITMASK_CODEC = Codec.LONG.listOf().xmap(
            longs -> {
                byte[] arr = new byte[BYTE_COUNT];
                int n = Math.min(longs.size(), BYTE_COUNT / 8);
                for (int i = 0; i < n; i++) {
                    long v = longs.get(i);
                    for (int b = 0; b < 8; b++) arr[i * 8 + b] = (byte) (v >> (b * 8));
                }
                return arr;
            },
            arr -> {
                List<Long> longs = new ArrayList<>(BYTE_COUNT / 8);
                for (int i = 0; i < BYTE_COUNT / 8; i++) {
                    long v = 0;
                    for (int b = 0; b < 8; b++) v |= ((long) (arr[i * 8 + b] & 0xFF)) << (b * 8);
                    longs.add(v);
                }
                return longs;
            }
    );

    public static final Codec<MapEntryData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BITMASK_CODEC.fieldOf("discovered_pixels").forGetter(d -> d.discoveredPixels),
                    ExpansionRecord.CODEC.listOf().fieldOf("expansions").forGetter(d -> d.expansions),
                    Waypoint.CODEC.listOf().fieldOf("waypoints").forGetter(d -> d.waypoints)
            ).apply(instance, MapEntryData::new)
    );
}
