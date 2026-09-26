package com.explorermap.data;

import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.waypoint.Waypoint;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class MapEntryData {

    public static final int MAP_SIZE    = 128;
    public static final int PIXEL_COUNT = MAP_SIZE * MAP_SIZE;
    public static final int BYTE_COUNT  = PIXEL_COUNT / 8;

    private static final int MAX_WAYPOINT_NAME_LENGTH = 64;
    private static final int MAX_ICON_ID_LENGTH = 128;
    private static final int MAX_WAYPOINTS_ON_LOAD = 4096;
    private static final int MAX_EXPANSIONS_ON_LOAD = 4096;

    private final byte[] discoveredPixels;
    private transient byte[] recentPixels;
    private transient boolean hasFadingPixels = false;

    private transient final Set<Long> processedStructureChunks = new HashSet<>();

    private final List<ExpansionRecord> expansions;
    private final List<Waypoint> waypoints;
    private int waypointCapacity;
    private int waypointShareCharges;

    private transient long discoveryGeneration = 0L;
    private transient long visualGeneration = 0L;

    public MapEntryData() {
        this.discoveredPixels = new byte[BYTE_COUNT];
        this.recentPixels = new byte[PIXEL_COUNT];
        this.expansions = new ArrayList<>();
        this.waypoints = new ArrayList<>();
        this.waypointCapacity = 0;
        this.waypointShareCharges = 0;
    }

    private MapEntryData(byte[] discoveredPixels, List<ExpansionRecord> expansions, List<Waypoint> waypoints) {
        this.discoveredPixels = discoveredPixels.length == BYTE_COUNT
                ? discoveredPixels.clone() : new byte[BYTE_COUNT];
        this.recentPixels = new byte[PIXEL_COUNT];
        this.expansions = new ArrayList<>(expansions);
        this.waypoints = new ArrayList<>(waypoints);
        this.waypointCapacity = 0;
        this.waypointShareCharges = 0;
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
        hasFadingPixels = true;
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
            hasFadingPixels = true;
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
        if (!hasFadingPixels) return false;
        boolean changed = false;
        boolean anyRemaining = false;
        for (int i = 0; i < recentPixels.length; i++) {
            int value = recentPixels[i] & 0xFF;
            if (value == 0) continue;
            int next = Math.max(0, value - 2);
            recentPixels[i] = (byte) next;
            changed = true;
            if (next != 0) anyRemaining = true;
        }
        if (changed) visualGeneration++;
        hasFadingPixels = anyRemaining;
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
                hasFadingPixels = true;
            }
        }
        return changed;
    }

    public boolean replaceBitmask(byte[] replacement) {
        boolean changed = false;
        int n = Math.min(replacement.length, BYTE_COUNT);
        for (int i = 0; i < BYTE_COUNT; i++) {
            byte next = i < n ? replacement[i] : 0;
            if (discoveredPixels[i] != next) {
                discoveredPixels[i] = next;
                changed = true;
            }
        }
        if (changed) {
            discoveryGeneration++;
            visualGeneration++;
            java.util.Arrays.fill(recentPixels, (byte) 0);
            hasFadingPixels = false;
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

    public Waypoint findWaypoint(UUID id) {
        if (id == null) return null;
        for (Waypoint wp : waypoints) {
            if (wp.id().equals(id)) return wp;
        }
        return null;
    }

    public void addWaypoint(Waypoint wp) {
        waypoints.removeIf(existing -> existing.id().equals(wp.id()));
        waypoints.add(wp);
    }

    public void removeWaypoint(UUID id) {
        if (id == null) return;
        waypoints.removeIf(wp -> wp.id().equals(id));
    }

    public void replaceAllWaypoints(List<Waypoint> newList) {
        waypoints.clear();
        waypoints.addAll(newList);
    }

    public int getWaypointCapacity() {
        return waypointCapacity;
    }

    public int getWaypointShareCharges() {
        return waypointShareCharges;
    }

    public void setWaypointCapacity(int capacity, int shareCharges) {
        waypointCapacity = Math.max(0, capacity);
        waypointShareCharges = Math.max(0, shareCharges);
    }

    public void addWaypointCapacity() {
        waypointCapacity += 5;
        waypointShareCharges++;
    }

    public boolean consumeWaypointShareCharge() {
        if (waypointShareCharges <= 0) return false;
        waypointShareCharges--;
        return true;
    }

    public boolean markStructureChunkProcessed(long chunkPosLong) {
        return processedStructureChunks.add(chunkPosLong);
    }

    public void unmarkStructureChunkProcessed(long chunkPosLong) {
        processedStructureChunks.remove(chunkPosLong);
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

    private static boolean isValidOnLoad(Waypoint wp) {
        if (wp == null || wp.id() == null) return false;
        if (wp.name() == null || wp.name().isBlank() || wp.name().length() > MAX_WAYPOINT_NAME_LENGTH) return false;
        if (wp.iconId() == null || wp.iconId().isBlank() || wp.iconId().length() > MAX_ICON_ID_LENGTH) return false;
        if (!Double.isFinite(wp.worldX()) || !Double.isFinite(wp.worldZ())) return false;
        return true;
    }

    public static final Codec<MapEntryData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BITMASK_CODEC.fieldOf("discovered_pixels").forGetter(d -> d.discoveredPixels),
                    ExpansionRecord.CODEC.listOf().fieldOf("expansions").forGetter(d -> d.expansions),
                    Waypoint.CODEC.listOf().fieldOf("waypoints").forGetter(d -> d.waypoints),
                    Codec.INT.optionalFieldOf("waypoint_capacity", 0).forGetter(d -> d.waypointCapacity),
                    Codec.INT.optionalFieldOf("waypoint_share_charges", 0).forGetter(d -> d.waypointShareCharges)
            ).apply(instance, (pixels, expansions, waypoints, capacity, shareCharges) -> {
                List<ExpansionRecord> safeExpansions = expansions.size() > MAX_EXPANSIONS_ON_LOAD
                        ? expansions.subList(0, MAX_EXPANSIONS_ON_LOAD) : expansions;

                List<Waypoint> safeWaypoints = new ArrayList<>(Math.min(waypoints.size(), MAX_WAYPOINTS_ON_LOAD));
                Set<UUID> seenIds = new HashSet<>();
                for (Waypoint wp : waypoints) {
                    if (safeWaypoints.size() >= MAX_WAYPOINTS_ON_LOAD) break;
                    if (!isValidOnLoad(wp)) continue;
                    if (!seenIds.add(wp.id())) continue; // duplicate id on disk - keep the first occurrence
                    safeWaypoints.add(wp);
                }

                MapEntryData data = new MapEntryData(pixels, safeExpansions, safeWaypoints);
                data.waypointCapacity = Math.max(0, capacity);
                data.waypointShareCharges = Math.max(0, shareCharges);
                return data;
            })
    );
}
