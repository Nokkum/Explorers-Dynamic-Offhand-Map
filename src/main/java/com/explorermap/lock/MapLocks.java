package com.explorermap.lock;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.MapIdentity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.MapPostProcessing;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public final class MapLocks {

    private MapLocks() {}

    public static boolean isLocked(MapItemSavedData state) {
        return state.locked;
    }

    // True if the stack is a filled map whose data is known (on the client: already received) and locked.
    public static boolean isLocked(ItemStack stack, Level level) {
        if (!ExplorerMapMod.isFilledMap(stack)) return false;
        MapItemSavedData state = MapIdentity.stateOf(stack, level);
        return state != null && isLocked(state);
    }

    // A locked copy of the given map item, under a newly allocated map id; the source stack is untouched.
    // Returns null if vanilla did not produce a locked copy.
    public static ItemStack lockedCopy(ServerLevel level, ItemStack source) {
        ItemStack copy = source.copyWithCount(1);
        copy.set(DataComponents.MAP_POST_PROCESSING, MapPostProcessing.LOCK);
        copy.getItem().onCraftedPostProcess(copy, level);

        int before = MapIdentity.rawIdOf(source);
        int after = MapIdentity.rawIdOf(copy);
        if (after < 0 || after == before || copy.has(DataComponents.MAP_POST_PROCESSING)) return null;

        MapItemSavedData state = MapIdentity.stateOf(copy, level);
        return state != null && isLocked(state) ? copy : null;
    }

    // A locked copy of an expansion tile, as a new map identity. A tile that is already locked is returned as is.
    // Returns null if the tile's map data is missing or vanilla did not produce a copy.
    public static MapIdentity lockedTileCopy(MinecraftServer server, MapIdentity tile) {
        MapItemSavedData data = MapIdentity.resolveAndVerify(server, tile);
        if (data == null) return null;
        if (isLocked(data)) return tile;

        ServerLevel world = server.getLevel(tile.dimension());
        if (world == null) return null;

        ItemStack carrier = new ItemStack(Items.FILLED_MAP);
        carrier.set(DataComponents.MAP_ID, new MapId(tile.mapId()));
        ItemStack copy = lockedCopy(world, carrier);
        return copy == null ? null : new MapIdentity(tile.dimension(), MapIdentity.rawIdOf(copy));
    }
}
