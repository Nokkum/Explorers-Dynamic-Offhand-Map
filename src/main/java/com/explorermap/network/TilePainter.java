package com.explorermap.network;

import com.explorermap.lock.MapLocks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

final class TilePainter {

    private TilePainter() {}

    static void paint(MapItem mapItem, ServerPlayer player, MapItemSavedData data) {
        // Vanilla only paints unlocked maps; this call goes around that, so a locked tile is skipped here.
        if (MapLocks.isLocked(data)) return;
        mapItem.update(player.level(), player, data);
    }
}
