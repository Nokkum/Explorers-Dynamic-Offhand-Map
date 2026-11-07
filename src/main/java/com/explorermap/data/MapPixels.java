package com.explorermap.data;

import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** World to map-pixel math shared by the server checks. */
public final class MapPixels {

    private MapPixels() {}

    public static int col(MapItemSavedData state, double worldX) {
        int scale = 1 << state.scale;
        return Math.floorDiv((int) Math.floor(worldX) - (state.centerX - 64 * scale), scale);
    }

    public static int row(MapItemSavedData state, double worldZ) {
        int scale = 1 << state.scale;
        return Math.floorDiv((int) Math.floor(worldZ) - (state.centerZ - 64 * scale), scale);
    }

    public static boolean isSet(byte[] mask, int col, int row) {
        if (mask == null || col < 0 || col >= MapEntryData.MAP_SIZE || row < 0 || row >= MapEntryData.MAP_SIZE) {
            return false;
        }
        int bit = row * MapEntryData.MAP_SIZE + col;
        return (mask[bit >> 3] & (1 << (bit & 7))) != 0;
    }

    public static boolean isWorldPositionSet(MapItemSavedData state, byte[] mask, double worldX, double worldZ) {
        return isSet(mask, col(state, worldX), row(state, worldZ));
    }
}
