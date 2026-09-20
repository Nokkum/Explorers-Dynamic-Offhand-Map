package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;

public final class MapStateLocator {

    private MapStateLocator() {}

    public static int findOrCreate(ServerWorld world, ExplorerMapSavedData savedData, int cx, int cz, byte scale) {
        Integer existing = savedData.findTileId(world, scale, cx, cz);
        if (existing != null && world.getMapState(new MapIdComponent(existing)) != null) {
            ExplorerMapMod.LOGGER.debug(
                    "[ExplorerMap] Reusing existing map #{} for center ({},{})", existing, cx, cz);
            return existing;
        }

        int newId = createMap(world, cx, cz, scale);
        if (newId >= 0) {
            savedData.recordTile(world, scale, cx, cz, newId);
        }
        ExplorerMapMod.LOGGER.info(
                "[ExplorerMap] Created new map #{} for center ({},{})", newId, cx, cz);
        return newId;
    }

    private static int createMap(ServerWorld world, int cx, int cz, byte scale) {
        ItemStack mapStack = FilledMapItem.createMap(world, cx, cz, scale, true, false);
        MapIdComponent id = mapStack.get(DataComponentTypes.MAP_ID);
        if (id == null) {
            ExplorerMapMod.LOGGER.error(
                    "[ExplorerMap] FilledMapItem.createMap() returned a stack with no MapIdComponent!");
            return -1;
        }
        return id.id();
    }
}
