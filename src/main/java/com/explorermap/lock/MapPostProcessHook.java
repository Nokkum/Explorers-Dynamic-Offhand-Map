package com.explorermap.lock;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.network.ExpansionFamilySync;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.MapPostProcessing;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.LinkedHashMap;
import java.util.Map;

// Locking and zooming out happen at the vanilla cartography table (or the crafting grid). Vanilla gives the result
// a new map id, so this carries what the mod stores for the old map over to it. It is driven by MapItemMixin,
// which calls it around MapItem#onCraftedPostProcess, the step where vanilla makes the new map.
public final class MapPostProcessHook {

    private record Pending(MapIdentity source, MapPostProcessing kind) {}

    // The map being processed on this thread, set before vanilla replaces its id and consumed after.
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();
    // Set while this hook locks the expansion tiles itself, so those copies do not trigger it again.
    private static final ThreadLocal<Boolean> COPYING_TILES = new ThreadLocal<>();

    private MapPostProcessHook() {}

    // Before vanilla post-processes the stack: remember which map is about to be locked or zoomed out.
    public static void before(ItemStack stack, Level level) {
        if (COPYING_TILES.get() != null) return;
        PENDING.remove();
        if (!(level instanceof ServerLevel)) return;
        MapPostProcessing kind = stack.get(DataComponents.MAP_POST_PROCESSING);
        if (kind != MapPostProcessing.LOCK && kind != MapPostProcessing.SCALE) return;
        MapIdentity source = MapIdentity.ofStack(stack, level);
        if (source != null) PENDING.set(new Pending(source, kind));
    }

    // After vanilla post-processed the stack: it now carries the new map's id.
    public static void after(ItemStack stack, Level level) {
        Pending pending = PENDING.get();
        if (pending == null) return;
        PENDING.remove();
        if (!(level instanceof ServerLevel serverLevel)) return;

        MapIdentity result = MapIdentity.ofStack(stack, level);
        if (result == null || result.equals(pending.source())) return;

        MinecraftServer server = serverLevel.getServer();
        if (server == null) return;
        ExplorerMapSavedData savedData = ExplorerMapSavedData.get(server);

        if (pending.kind() == MapPostProcessing.LOCK) {
            carryToLocked(server, savedData, pending.source(), result);
        } else {
            carryToZoomedOut(savedData, pending.source(), result, MapIdentity.stateOf(stack, level));
        }
    }

    private static void carryToLocked(MinecraftServer server, ExplorerMapSavedData savedData,
                                      MapIdentity source, MapIdentity lockedRoot) {
        // The root first, then every expansion tile. A tile that cannot be copied is left out of the locked map.
        Map<MapIdentity, MapIdentity> copies = new LinkedHashMap<>();
        copies.put(source, lockedRoot);
        COPYING_TILES.set(Boolean.TRUE);
        try {
            for (MapIdentity map : ExpansionFamilySync.familyOf(savedData, source).maps()) {
                if (map.equals(source)) continue;
                MapIdentity copy = MapLocks.lockedTileCopy(server, map);
                if (copy != null) copies.put(map, copy);
            }
        } finally {
            COPYING_TILES.remove();
        }
        copies.forEach((from, to) -> {
            if (!from.equals(to)) savedData.copyMapData(from, to, copies);
        });

        ExplorerMapMod.LOGGER.info("[ExplorerMap] Map {} locked as {} ({} maps)",
                source.asKey(), lockedRoot.asKey(), copies.size());
    }

    private static void carryToZoomedOut(ExplorerMapSavedData savedData, MapIdentity source,
                                         MapIdentity zoomed, MapItemSavedData zoomedState) {
        if (zoomedState == null) return;
        var sourceEntry = savedData.get(source);
        int total = sourceEntry != null ? sourceEntry.getWaypoints().size() : 0;
        int carried = savedData.copyForRescale(source, zoomed,
                zoomedState.centerX, zoomedState.centerZ, 1 << zoomedState.scale);

        ExplorerMapMod.LOGGER.info("[ExplorerMap] Map {} zoomed out as {} ({} of {} waypoints carried over)",
                source.asKey(), zoomed.asKey(), carried, total);
    }
}
