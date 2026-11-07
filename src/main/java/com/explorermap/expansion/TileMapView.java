package com.explorermap.expansion;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** What the renderer needs from a tile's map data: from vanilla map data when the client has it, or synced. */
public record TileMapView(byte[] colors, int blocksPerPixel, int centerX, int centerZ, ResourceKey<Level> dimension) {

    public static TileMapView of(MapItemSavedData state) {
        return new TileMapView(state.colors, 1 << state.scale, state.centerX, state.centerZ, state.dimension);
    }
}
