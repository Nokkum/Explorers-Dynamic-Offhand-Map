package com.explorermap.mod.data;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.item.map.MapState;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-only, in-memory mirror of per-map exploration data.
 *
 * Keyed by the plain raw integer map ID. MapIdComponent IDs are drawn from
 * a single counter shared by the whole server (never reused across
 * dimensions), so the raw int alone is already a stable, unique key — no
 * dimension qualification needed (see ExplorerMapSavedData's doc comment
 * for the fuller reasoning on why qualifying by dimension was tried and
 * reverted).
 *
 * Why this exists (and why it is NOT ExplorerMapSavedData)
 * ───────────────────────────────────────────────────────
 * ExplorerMapSavedData is a server-owned PersistentState — the client has
 * no access to it at all. This class is the client's local, purely
 * in-memory picture of the same data, built up entirely from what the
 * server has told it via ExplorationEngine's own ray-casting, plus
 * GrantExpansionPayload, SyncWaypointsPayload, and
 * SyncDiscoveryPayload.Broadcast. It is cleared on disconnect, since a
 * fresh copy is re-synced from the server on the next join anyway.
 */
@Environment(EnvType.CLIENT)
public final class ClientMapCache {

    private static final Map<Integer, MapEntryData> ENTRIES = new HashMap<>();

    private ClientMapCache() {}

    /** Returns the local mirror entry for this map, creating an empty one if absent. */
    public static MapEntryData getOrCreate(MapState state, int mapId) {
        return ENTRIES.computeIfAbsent(mapId, k -> new MapEntryData());
    }

    /** Returns the local mirror entry for this map, or null if never touched. */
    public static MapEntryData get(MapState state, int mapId) {
        return ENTRIES.get(mapId);
    }

    /** Clears the entire client-side cache. Call on disconnect. */
    public static void clearAll() {
        ENTRIES.clear();
    }
}
