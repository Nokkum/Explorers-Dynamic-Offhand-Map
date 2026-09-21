package com.explorermap.data;

import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.waypoint.Waypoint;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

public final class MapEntryData {

    public static final int MAP_SIZE    = 128;
    public static final int PIXEL_COUNT = MAP_SIZE * MAP_SIZE;
    public static final int BYTE_COUNT  = PIXEL_COUNT / 8;

    private final byte[] discoveredPixels;
    private transient byte[] recentPixels;

    private final List<ExpansionRecord> expansions;
    private final List<Waypoint> waypoints;

    private transient long discoveryGeneration = 0L;
    private transient long visualGeneration = 0L;

    public MapEntryData() {
        this.discoveredPixels = new byte[BYTE_COUNT];
        this.recentPixels = new byte[PIXEL_COUNT];
        this.expansions = new ArrayList<>();
        this.waypoints = new ArrayList<>();
    }

    private MapEntryData(byte[] discoveredPixels, List<ExpansionRecord> expansions, List<Waypoint> waypoints) {
        this.discoveredPixels = discoveredPixels.length == BYTE_COUNT
                ? discoveredPixels.clone() : new byte[BYTE_COUNT];
        this.recentPixels = new byte[PIXEL_COUNT];
        this.expansions = new ArrayList<>(expansions);
        this.waypoints = new ArrayList<>(waypoints);
    }

    public boolean discover(int col, int row) {
        if (col < 0 || col >= MAP_SIZE || row < 0 || row >= MAP_SIZE) return false;
        int bit = row * MAP_SIZE + col;
        int idx = bit >> 3, shift = bit & 7;
        byte before = discoveredPixels[idx];
        discoveredPixels[idx] |= (byte) (1 << shift);
        int pixel = row * MAP_SIZE + col;
        boolean refreshed = (recentPixels[pixel] & 0xFF) < 255;
        recentPixels[pixel] = (byte) 0xFF;
        if (discoveredPixels[idx] != before) {
            discoveryGeneration++;
            visualGeneration++;
            return true;
        }
        if (refreshed) visualGeneration++;
        return false;
    }

    public boolean discoverAll() {
        boolean changed = false;
        for (int i = 0; i < BYTE_COUNT; i++) {
            byte before = discoveredPixels[i];
            discoveredPixels[i] = (byte) 0xFF;
            if (discoveredPixels[i] != before) changed = true;
        }
        if (changed) {
            discoveryGeneration++;
            visualGeneration++;
            java.util.Arrays.fill(recentPixels, (byte) 0xFF);
        }
        return changed;
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

    public long getVisualGeneration() {
        return visualGeneration;
    }

    public int recentValue(int col, int row) {
        if (col < 0 || col >= MAP_SIZE || row < 0 || row >= MAP_SIZE) return 0;
        return recentPixels[row * MAP_SIZE + col] & 0xFF;
    }

    public boolean tickRecency() {
        boolean changed = false;
        for (int i = 0; i < recentPixels.length; i++) {
            int value = recentPixels[i] & 0xFF;
            if (value == 0) continue;
            recentPixels[i] = (byte) Math.max(0, value - 2);
            changed = true;
        }
        if (changed) visualGeneration++;
        return changed;
    }

    public boolean mergeBitmask(byte[] other) {
        boolean changed = false;
        int n = Math.min(other.length, BYTE_COUNT);
        for (int i = 0; i < n; i++) {
            byte before = discoveredPixels[i];
            discoveredPixels[i] |= other[i];
            if (discoveredPixels[i] != before) {
                discoveryGeneration++;
                visualGeneration++;
                changed = true;
                for (int bit = 0; bit < 8; bit++) {
                    if ((other[i] & (1 << bit)) != 0) recentPixels[i * 8 + bit] = (byte) 0xFF;
                }
            }
        }
        return changed;
    }

    public List<ExpansionRecord> getExpansions() {
        return List.copyOf(expansions);
    }

    public void addExpansion(ExpansionRecord record) {
        expansions.add(record);
    }

    public boolean hasExpansion(ExpansionRecord.Direction dir) {
        return expansions.stream().anyMatch(r -> r.direction() == dir);
    }

    public List<Waypoint> getWaypoints() {
        return List.copyOf(waypoints);
    }

    public void addWaypoint(Waypoint wp) {
        waypoints.removeIf(existing -> existing.name().equals(wp.name()));
        waypoints.add(wp);
    }

    public void removeWaypoint(String name) {
        waypoints.removeIf(wp -> wp.name().equals(name));
    }

    public void replaceAllWaypoints(List<Waypoint> newList) {
        waypoints.clear();
        waypoints.addAll(newList);
    }

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
