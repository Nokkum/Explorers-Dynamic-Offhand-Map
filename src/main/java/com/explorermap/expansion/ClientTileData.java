package com.explorermap.expansion;

import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapIdentity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.HashMap;
import java.util.Map;

/** Colors of expansion tiles as synced by the server (the client has no vanilla map data for them). */
@Environment(EnvType.CLIENT)
public final class ClientTileData {

    private static final Map<MapIdentity, byte[]> COLORS = new HashMap<>();

    private ClientTileData() {}

    public static void put(MapIdentity mapId, byte[] colors) {
        COLORS.put(mapId, colors);
        ClientMapCache.getOrCreate(mapId).touchVisual();
    }

    public static byte[] get(MapIdentity mapId) {
        return COLORS.get(mapId);
    }

    public static void clear() {
        COLORS.clear();
    }
}
