package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;

public final class MapStateLocator {

    private MapStateLocator() {}

    public static MapIdentity findOrCreate(ServerLevel world, ExplorerMapSavedData savedData, int cx, int cz, byte scale) {
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

    private static MapIdentity verifyIndexedMap(ServerLevel world, MapIdentity indexed) {
        if (indexed == null) return null;

        if (!indexed.dimension().equals(world.dimension())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] Tile index returned {} for a lookup in dimension {} - ignoring it",
                    indexed.asKey(), world.dimension().identifier());
            return null;
        }

        var state = world.getMapData(new MapId(indexed.mapId()));
        if (state == null) return null;

        if (!state.dimension.equals(world.dimension())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] Indexed map {} resolved to a MapItemSavedData in dimension {} instead - ignoring it",
                    indexed.asKey(), state.dimension.identifier());
            return null;
        }

        return indexed;
    }

    private static MapIdentity createMap(ServerLevel world, int cx, int cz, byte scale) {
        ItemStack mapStack = MapItem.create(world, cx, cz, scale, true, false);
        MapId id = mapStack.get(DataComponents.MAP_ID);
        if (id == null) {
            ExplorerMapMod.LOGGER.error(
                    "[ExplorerMap] MapItem.create() returned a stack with no MapId!");
            return null;
        }
        return MapIdentity.of(world, id.id());
    }
}
