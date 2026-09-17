package com.explorermap.mod.data;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.HashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class ClientMapCache {

    private static final Map<Integer, MapEntryData> ENTRIES = new HashMap<>();

    private ClientMapCache() {}

    public static MapEntryData getOrCreate(int mapId) {
        return ENTRIES.computeIfAbsent(mapId, k -> new MapEntryData());
    }

    public static MapEntryData get(int mapId) {
        return ENTRIES.get(mapId);
    }

    public static void clearAll() {
        ENTRIES.clear();
    }
}
