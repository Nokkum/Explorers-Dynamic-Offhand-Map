package com.explorermap.mod.data;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.world.World;

/**
 * Central helper for resolving map identity under Minecraft 1.21.1's actual
 * data-component-based map API.
 *
 * Earlier revisions of this mod used FilledMapItem.getMapId(ItemStack) and
 * FilledMapItem.getMapName(int), returning a plain int and a "map_N" string
 * key respectively. Those methods do not exist in 1.21.1 — they were removed
 * around 1.20.5 when map identity moved to the MapIdComponent data component
 * (net.minecraft.component.type.MapIdComponent, stored under
 * DataComponentTypes.MAP_ID). This class is the single place that touches
 * that API so the rest of the mod never has to.
 */
public final class MapIdentity {

    private MapIdentity() {}

    /** Returns the MapIdComponent on this stack, or null if it's not a filled map / has no ID yet. */
    public static MapIdComponent idOf(ItemStack stack) {
        return stack.get(DataComponentTypes.MAP_ID);
    }

    /** Convenience: raw integer ID, or -1 if the stack has no map ID component. */
    public static int rawIdOf(ItemStack stack) {
        MapIdComponent id = idOf(stack);
        return id != null ? id.id() : -1;
    }

    /** Resolves the MapState for a stack in the given world, or null if absent. */
    public static MapState stateOf(ItemStack stack, World world) {
        return FilledMapItem.getMapState(stack, world);
    }

    /** Resolves the MapState for a raw integer ID in the given world, or null if absent. */
    public static MapState stateOf(int rawId, World world) {
        return world.getMapState(new MapIdComponent(rawId));
    }
}
