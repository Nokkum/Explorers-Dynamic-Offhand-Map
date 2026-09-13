package com.explorermap.mod.expansion;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.attachment.MapDiscoveryAttachment;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.world.storage.MapState;

/**
 * Client-side expansion helper.
 *
 * The authoritative expansion logic lives entirely on the server
 * (RequestExpansionPayload → MapStateLocator → GrantExpansionPayload).
 *
 * This class provides two things for the UI:
 *
 *   canExpand()     – Returns true if the player's inventory contains the
 *                     required items for the requested expansion type.
 *                     Used by FullMapScreen to enable/disable direction buttons.
 *
 *   alreadyExpanded() – Returns true if the current map attachment already
 *                       has an expansion in this direction, so the button can
 *                       show a "done" state rather than being active.
 *
 * Neither method touches world state or sends any packets.
 */
@Environment(EnvType.CLIENT)
public final class ExpansionHandler {

    private ExpansionHandler() {}

    /**
     * Returns true if the player has the resources to expand.
     * Standard: 1× Paper.
     * HD:       1× Paper + 1× Ink Sac + 1× Compass.
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

    /**
     * Returns true if the current off-hand map is already expanded in this direction.
     */
    public static boolean alreadyExpanded(ClientPlayerEntity player,
                                           ExpansionRecord.Direction direction) {
        var offHand = player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand) || player.getWorld() == null) return false;

        MapState state = net.minecraft.item.FilledMapItem.getMapState(offHand, player.getWorld());
        if (state == null) return false;

        MapDiscoveryAttachment attachment = ExplorerMapMod.getOrCreate(state);
        return attachment.hasExpansion(direction);
    }
}
