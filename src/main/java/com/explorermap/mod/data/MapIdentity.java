package com.explorermap.mod.data;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.world.World;

public final class MapIdentity {

    private MapIdentity() {}

    public static MapIdComponent idOf(ItemStack stack) {
        return stack.get(DataComponentTypes.MAP_ID);
    }

    public static int rawIdOf(ItemStack stack) {
        MapIdComponent id = idOf(stack);
        return id != null ? id.id() : -1;
    }

    public static MapState stateOf(ItemStack stack, World world) {
        return FilledMapItem.getMapState(stack, world);
    }

    public static MapState stateOf(int rawId, World world) {
        return world.getMapState(new MapIdComponent(rawId));
    }
}
