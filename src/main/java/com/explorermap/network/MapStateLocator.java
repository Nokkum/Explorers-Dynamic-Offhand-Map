package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;

public final class MapStateLocator {

    private MapStateLocator() {}

    public static MapIdentity findOrCreate(ServerWorld world, ExplorerMapSavedData savedData, int cx, int cz, byte scale) {
        MapIdentity existing = savedData.findTileId(world, scale, cx, cz);
        if (existing != null && world.getMapState(new MapIdComponent(existing.mapId())) != null) {
            ExplorerMapMod.LOGGER.debug(
                    "[ExplorerMap] Reusing existing map {} for center ({},{})", existing.asKey(), cx, cz);
            return existing;
        }

        MapIdentity created = createMap(world, cx, cz, scale);
        if (created != null) {
            savedData.recordTile(world, scale, cx, cz, created);
            ExplorerMapMod.LOGGER.info(
                    "[ExplorerMap] Created new map {} for center ({},{})", created.asKey(), cx, cz);
        } else {
            ExplorerMapMod.LOGGER.error(
                    "[ExplorerMap] Failed to create a new map for center ({},{})", cx, cz);
        }
        return created;
    }

    private static MapIdentity createMap(ServerWorld world, int cx, int cz, byte scale) {
        ItemStack mapStack = FilledMapItem.createMap(world, cx, cz, scale, true, false);
        MapIdComponent id = mapStack.get(DataComponentTypes.MAP_ID);
        if (id == null) {
            ExplorerMapMod.LOGGER.error(
                    "[ExplorerMap] FilledMapItem.createMap() returned a stack with no MapIdComponent!");
            return null;
        }
        return MapIdentity.of(world, id.id());
    }
}
