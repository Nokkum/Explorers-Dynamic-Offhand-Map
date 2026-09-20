package com.explorermap.expansion;

import com.explorermap.ExplorerMapMod;
import com.explorermap.config.ExplorerMapConfig;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapIdentity;
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
        if (player.isCreative()) return true;

        var inv = player.getInventory();
        int cost = ExplorerMapConfig.get().expansionPaperCost;

        int paperHeld = 0;
        for (int i = 0; i < inv.size(); i++) {
            var slot = inv.getStack(i);
            if (slot.isOf(Items.PAPER)) paperHeld += slot.getCount();
        }
        if (paperHeld < cost) return false;

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

        var entry = ClientMapCache.get(mapId);
        return entry != null && entry.hasExpansion(direction);
    }
}
