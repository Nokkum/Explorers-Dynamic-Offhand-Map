package com.explorermap.mod.dimension;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;
import net.minecraft.world.World;

/**
 * Dimension relevance checks for the held map.
 *
 * An earlier revision of this class inferred a map's dimension from
 * coordinate heuristics (comparing player position against map center,
 * with a hand-rolled ×8 conversion for the Nether) because it assumed
 * MapState carried no dimension information of its own. That assumption
 * was wrong: MapState has always had a `final RegistryKey<World> dimension`
 * field. Reading it directly makes every coordinate heuristic unnecessary —
 * there is also no coordinate conversion to do, since each dimension has
 * its own independent block-coordinate space for maps created within it;
 * a map made in the Nether stores Nether coordinates, not Overworld
 * coordinates scaled down.
 */
@Environment(EnvType.CLIENT)
public final class DimensionMapTracker {

    private DimensionMapTracker() {}

    /**
     * Returns true if the given map was created in the dimension the player
     * currently occupies. Vanilla itself will not let a map update outside
     * its own dimension, so this mirrors that same restriction for our
     * exploration engine and HUD.
     */
    public static boolean isMapRelevantForCurrentDimension(ClientPlayerEntity player, MapState mapState) {
        return mapState.dimension.equals(player.getWorld().getRegistryKey());
    }

    /** Returns true if the player is currently in the Overworld. */
    public static boolean isOverworld(ClientPlayerEntity player) {
        return player.getWorld().getRegistryKey().equals(World.OVERWORLD);
    }

    /**
     * Human-readable dimension label for the HUD status line, using
     * translation keys for the three vanilla dimensions and falling back to
     * a formatted registry path for modded ones (whose translation keys we
     * can't know in advance).
     */
    public static String dimensionLabel(ClientPlayerEntity player) {
        var dim = player.getWorld().getRegistryKey();
        if (dim.equals(World.OVERWORLD)) return Text.translatable("explorermap.dimension.overworld").getString();
        if (dim.equals(World.NETHER))    return Text.translatable("explorermap.dimension.nether").getString();
        if (dim.equals(World.END))       return Text.translatable("explorermap.dimension.end").getString();
        String path = dim.getValue().getPath();
        return Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
    }
}
