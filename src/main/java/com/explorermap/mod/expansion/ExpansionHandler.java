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

@Environment(EnvType.CLIENT)
public final class ExpansionHandler {

    private ExpansionHandler() {}

    public static boolean canExpand(ClientPlayerEntity player, boolean highDetail) {
        var inv = player.getInventory();
        if (!inv.contains(Items.PAPER.getDefaultStack())) return false;
        if (highDetail) {
            if (!inv.contains(Items.INK_SAC.getDefaultStack())) return false;
            if (!inv.contains(Items.COMPASS.getDefaultStack())) return false;
        }
        return true;
    }

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
