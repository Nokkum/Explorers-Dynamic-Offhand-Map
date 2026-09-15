package com.explorermap.mod.attachment;

import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.waypoint.Waypoint;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

public class MapDiscoveryAttachment {

    public static final int MAP_SIZE = 128;
    public static final int PIXEL_COUNT = MAP_SIZE * MAP_SIZE; // 16 384
    public static final int BYTE_COUNT  = PIXEL_COUNT / 8;     //  2 048

    private final byte[] discoveredPixels;

    private final List<ExpansionRecord> expansions;
    private final List<Waypoint>        waypoints;

    private volatile long discoveryGeneration = 0L;

    public MapDiscoveryAttachment() {
        this.discoveredPixels = new byte[BYTE_COUNT];
        this.expansions       = new ArrayList<>();
        this.waypoints        = new ArrayList<>();
    }

    public MapDiscoveryAttachment(byte[] discoveredPixels,
                                  List<ExpansionRecord> expansions,
                                  List<Waypoint> waypoints) {
        this.discoveredPixels = discoveredPixels.length == BYTE_COUNT
                ? discoveredPixels.clone()
                : new byte[BYTE_COUNT];
        this.expansions = new ArrayList<>(expansions);
        this.waypoints  = new ArrayList<>(waypoints);
    }

    public synchronized void discover(int col, int row) {
        if (col < 0 || col >= MAP_SIZE || row < 0 || row >= MAP_SIZE) return;
        int bit   = row * MAP_SIZE + col;
        int index = bit >> 3;
        int shift = bit & 7;
        byte before = discoveredPixels[index];
        discoveredPixels[index] |= (byte) (1 << shift);
        if (discoveredPixels[index] != before) discoveryGeneration++;
    }

    public long getDiscoveryGeneration() { return discoveryGeneration; }

    public boolean isDiscovered(int col, int row) {
        if (col < 0 || col >= MAP_SIZE || row < 0 || row >= MAP_SIZE) return false;
        int bit   = row * MAP_SIZE + col;
        int index = bit >> 3;
        int shift = bit & 7;
        return (discoveredPixels[index] & (1 << shift)) != 0;
    }

    public byte[] getDiscoveredPixelsCopy() {
        return discoveredPixels.clone();
    }

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

    public float discoveryFraction() {
        int count = 0;
        for (byte b : discoveredPixels) count += Integer.bitCount(b & 0xFF);
        return (float) count / PIXEL_COUNT;
    }

    public List<ExpansionRecord> getExpansions() { return expansions; }

    public void addExpansion(ExpansionRecord record) {
        expansions.add(record);
    }

    public boolean hasExpansion(ExpansionRecord.Direction dir) {
        return expansions.stream().anyMatch(r -> r.direction() == dir);
    }

    public List<Waypoint> getWaypoints() { return waypoints; }

    public void addWaypoint(Waypoint wp) {
        waypoints.add(wp);
    }

    public void removeWaypoint(String name) {
        waypoints.removeIf(wp -> wp.name().equals(name));
    }

    public synchronized void syncWaypoints(java.util.List<Waypoint> incoming) {
        waypoints.clear();
        waypoints.addAll(incoming);
    }
    
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
