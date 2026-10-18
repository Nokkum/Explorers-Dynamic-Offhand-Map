package com.explorermap.expansion;

import com.explorermap.ExplorerMapMod;
import com.explorermap.config.ExplorerMapConfig;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapIdentity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.InteractionHand;

@Environment(EnvType.CLIENT)
public final class ExpansionHandler {

    private ExpansionHandler() {}

    public static boolean canExpand(LocalPlayer player, boolean highDetail) {
        if (player.isCreative()) return true;

        var inv = player.getInventory();
        int cost = ExplorerMapConfig.get().expansionPaperCost;

        int paperHeld = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var slot = inv.getItem(i);
            if (slot.is(Items.PAPER)) paperHeld += slot.getCount();
        }
        if (paperHeld < cost) return false;

        if (highDetail) {
            if (!hasItem(inv, Items.INK_SAC)) return false;
            if (!hasItem(inv, Items.COMPASS)) return false;
        }
        return true;
    }

    private static boolean hasItem(Inventory inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) return true;
        }
        return false;
    }

    public static boolean alreadyExpanded(LocalPlayer player, ExpansionRecord.Direction direction) {
        var offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand) || player.level() == null) return false;

        MapItemSavedData state = MapIdentity.stateOf(offHand, player.level());
        if (state == null) return false;

        int rawMapId = MapIdentity.rawIdOf(offHand);
        if (rawMapId < 0) return false;

        var entry = ClientMapCache.get(MapIdentity.of(state, rawMapId));
        return entry != null && entry.hasExpansion(direction);
    }
}
