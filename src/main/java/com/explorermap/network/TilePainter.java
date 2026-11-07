package com.explorermap.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Paints a map the player is not holding using vanilla's own routine, so expansion tiles fill in as the
 * player explores them. This is the one call into vanilla map internals; if its signature changes in a
 * future Minecraft version, this is the only place to adjust.
 */
final class TilePainter {

    private TilePainter() {}

    static void paint(MapItem mapItem, ServerPlayer player, MapItemSavedData data) {
        mapItem.update(player.level(), player, data);
    }
}
