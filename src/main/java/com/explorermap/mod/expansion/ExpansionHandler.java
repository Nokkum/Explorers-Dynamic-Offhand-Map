package com.explorermap.mod.expansion;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.data.ClientMapCache;
import com.explorermap.mod.data.MapIdentity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.util.Hand;

/**
 * Client-side expansion helper.
 *
 * The authoritative expansion logic lives entirely on the server
 * (RequestExpansionPayload → MapStateLocator → GrantExpansionPayload).
 * This class only reads the client's local mirror (ClientMapCache) to decide
 * how to draw the UI — it never mutates anything or sends packets itself.
 */
@Environment(EnvType.CLIENT)
public final class ExpansionHandler {

    private ExpansionHandler() {}

    /**
     * Returns true if the player has the resources to expand.
     * Standard: 1× Paper. HD: 1× Paper + 1× Ink Sac + 1× Compass.
     */
    public static boolean canExpand(ClientPlayerEntity player, boolean highDetail) {
        var inv = player.getInventory();
        if (!inv.contains(Items.PAPER.getDefaultStack())) return false;
        if (highDetail) {
            if (!inv.contains(Items.INK_SAC.getDefaultStack())) return false;
            if (!inv.contains(Items.COMPASS.getDefaultStack())) return false;
        }
        return true;
    }

    /** Returns true if the current off-hand map is already expanded in this direction. */
    public static boolean alreadyExpanded(ClientPlayerEntity player, ExpansionRecord.Direction direction) {
        var offHand = player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand) || player.getWorld() == null) return false;

        MapState state = MapIdentity.stateOf(offHand, player.getWorld());
        if (state == null) return false;

        int mapId = MapIdentity.rawIdOf(offHand);
        if (mapId < 0) return false;

        var entry = ClientMapCache.get(state, mapId);
        return entry != null && entry.hasExpansion(direction);
    }
}
