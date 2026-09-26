package com.explorermap.data;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.HashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class ClientMapCache {

    private static final Map<MapIdentity, MapEntryData> ENTRIES = new HashMap<>();

    private ClientMapCache() {}

    public static MapEntryData getOrCreate(MapIdentity mapId) {
        return ENTRIES.computeIfAbsent(mapId, k -> new MapEntryData());
    }

    public static MapEntryData get(MapIdentity mapId) {
        return ENTRIES.get(mapId);
    }

    public static void tickRecency() {
        for (MapEntryData entry : ENTRIES.values()) {
            entry.tickRecency();
        }
    }

    public static void clearAll() {
        ENTRIES.clear();
    }
}
