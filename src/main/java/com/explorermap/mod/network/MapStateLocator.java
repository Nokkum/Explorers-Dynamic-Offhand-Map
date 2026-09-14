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

/**
 * Finds an existing vanilla MapState whose center is within one pixel's
 * tolerance of (targetX, targetZ) at the given scale, or creates a new one.
 *
 * Correct 1.21.1 map-identity handling
 * ─────────────────────────────────────
 * Map identity is a MapIdComponent (a record wrapping a single int), stored
 * on the ItemStack under DataComponentTypes.MAP_ID. There is no more
 * "map_N" string key, no more FilledMapItem.getMapId(ItemStack) returning
 * a raw Integer, and — critically — ServerWorld has no read-only "how many
 * maps exist" accessor. World.increaseAndGetMapId() allocates AND returns a
 * new ID as one atomic, side-effecting operation; it must only be called
 * when a new map is genuinely about to be created, never as a peek.
 *
 * FilledMapItem.createMap(ServerWorld, x, z, scale, showIcons, unlimitedTracking)
 * already performs that allocation internally and returns a fully-registered
 * ItemStack with its MapIdComponent set — so the correct way to learn the
 * ID of a newly created map is to read it back off that stack afterward,
 * never to call increaseAndGetMapId() ourselves.
 *
 * Search strategy for reuse
 * ──────────────────────────
 * Vanilla doesn't expose an index of "all currently existing map IDs" either,
 * so finding an existing tile still requires scanning candidate IDs. We scan
 * up to a generous ceiling, stopping after a run of consecutive misses,
 * exactly as before — the difference here is every lookup now goes through
 * MapIdComponent + World.getMapState(MapIdComponent), which is the real API,
 * rather than a string key that doesn't exist.
 */
public final class MapStateLocator {

    private static final int NULL_RUN_LIMIT = 64;
    private static final int MAX_SCAN = 1 << 16;

    private MapStateLocator() {}

    /**
     * Returns the raw integer map ID of a MapState centered near (cx, cz)
     * at the given scale, in the given dimension. Creates a new map if none
     * is found. The dimension parameter matters because MapState now carries
     * its own dimension field — a tile must only ever be reused if it was
     * created in the same dimension the player is currently exploring.
     */
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

    /**
     * Creates a new vanilla map and returns its assigned integer ID, read
     * back from the created ItemStack's own MapIdComponent — never derived
     * from a separate call to the world's ID counter.
     */
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
