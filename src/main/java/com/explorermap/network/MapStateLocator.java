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
        MapIdentity indexed = savedData.findTileId(world, scale, cx, cz);
        MapIdentity verified = verifyIndexedMap(world, indexed);

        if (verified != null) {
            ExplorerMapMod.LOGGER.debug(
                    "[ExplorerMap] Reusing existing map {} for center ({},{})", verified.asKey(), cx, cz);
            return verified;
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

    private static MapIdentity verifyIndexedMap(ServerWorld world, MapIdentity indexed) {
        if (indexed == null) return null;

        if (!indexed.dimension().equals(world.getRegistryKey())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] Tile index returned {} for a lookup in dimension {} - ignoring it",
                    indexed.asKey(), world.getRegistryKey().getValue());
            return null;
        }

        var state = world.getMapState(new MapIdComponent(indexed.mapId()));
        if (state == null) return null;

        if (!state.dimension.equals(world.getRegistryKey())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] Indexed map {} resolved to a MapState in dimension {} instead - ignoring it",
                    indexed.asKey(), state.dimension.getValue());
            return null;
        }

        return indexed;
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
