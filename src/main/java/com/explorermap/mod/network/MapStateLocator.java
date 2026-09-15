package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

public final class MapStateLocator {

    private static final int NULL_RUN_LIMIT = 64;
    private static final int MAX_SCAN = 1 << 16;

    private MapStateLocator() {}

    public static int findOrCreate(ServerWorld world, int cx, int cz, byte scale) {
        RegistryKey<World> dimension = world.getRegistryKey();
        int tolerance = Math.max(1, (1 << scale) / 2);

        int nullRun = 0;
        for (int id = 0; id < MAX_SCAN; id++) {
            MapState state = world.getMapState(new MapIdComponent(id));
            if (state == null) {
                if (++nullRun > NULL_RUN_LIMIT) break;
                continue;
            }
            nullRun = 0;

            if (state.scale != scale) continue;
            if (!state.dimension.equals(dimension)) continue;
            if (Math.abs(state.centerX - cx) <= tolerance
             && Math.abs(state.centerZ - cz) <= tolerance) {
                ExplorerMapMod.LOGGER.debug(
                        "[ExplorerMap] Reusing existing map #{} for center ({},{})", id, cx, cz);
                return id;
            }
        }

        int newId = createMap(world, cx, cz, scale);
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
