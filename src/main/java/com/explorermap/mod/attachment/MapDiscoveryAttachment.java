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
        discoveredPixels[index] |= (byte) (1 << shift);
    }

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

    // ── Codec ─────────────────────────────────────────────────────────────

    public static final Codec<MapDiscoveryAttachment> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.BYTE_BUFFER.xmap(
                            buf -> {
                                byte[] arr = new byte[BYTE_COUNT];
                                buf.get(arr);
                                return arr;
                            },
                            arr -> java.nio.ByteBuffer.wrap(arr)
                    ).fieldOf("discovered_pixels").forGetter(a -> a.discoveredPixels),

                    ExpansionRecord.CODEC.listOf().fieldOf("expansions")
                            .forGetter(a -> a.expansions),

                    Waypoint.CODEC.listOf().fieldOf("waypoints")
                            .forGetter(a -> a.waypoints)

            ).apply(instance, MapDiscoveryAttachment::new)
    );
}
