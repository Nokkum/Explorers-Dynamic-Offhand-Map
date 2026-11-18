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

import java.util.LinkedHashMap;
import java.util.Map;

// Locking happens at the vanilla cartography table (map + glass pane). Vanilla gives the locked copy a new map id,
// so this carries the mod's data (discovery, waypoints, expansion tiles) over to it. It is driven by
// MapItemMixin, which calls it around MapItem#onCraftedPostProcess, the step where vanilla makes the copy.
public final class MapLockHook {

    // The map a lock is in progress for on this thread, set before vanilla copies it and consumed after.
    private static final ThreadLocal<MapIdentity> PENDING = new ThreadLocal<>();
    // Set while this hook locks the expansion tiles itself, so those copies do not trigger it again.
    private static final ThreadLocal<Boolean> COPYING_TILES = new ThreadLocal<>();

    private MapLockHook() {}

    // Before vanilla post-processes the stack: remember which map is about to be locked.
    public static void before(ItemStack stack, Level level) {
        if (COPYING_TILES.get() != null) return;
        PENDING.remove();
        if (!(level instanceof ServerLevel)) return;
        if (stack.get(DataComponents.MAP_POST_PROCESSING) != MapPostProcessing.LOCK) return;
        MapIdentity source = MapIdentity.ofStack(stack, level);
        if (source != null) PENDING.set(source);
    }

    // After vanilla post-processed the stack: it now carries the locked copy's map id.
    public static void after(ItemStack stack, Level level) {
        MapIdentity source = PENDING.get();
        if (source == null) return;
        PENDING.remove();
        if (!(level instanceof ServerLevel serverLevel)) return;

        MapIdentity lockedRoot = MapIdentity.ofStack(stack, level);
        if (lockedRoot == null || lockedRoot.equals(source)) return;

        MinecraftServer server = serverLevel.getServer();
        if (server == null) return;
        ExplorerMapSavedData savedData = ExplorerMapSavedData.get(server);

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
}
