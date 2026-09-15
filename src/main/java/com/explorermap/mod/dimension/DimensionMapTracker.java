package com.explorermap.mod.dimension;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;
import net.minecraft.world.World;

@Environment(EnvType.CLIENT)
public final class DimensionMapTracker {

    private DimensionMapTracker() {}

    public static boolean isMapRelevantForCurrentDimension(ClientPlayerEntity player, MapState mapState) {
        return mapState.dimension.equals(player.getWorld().getRegistryKey());
    }

    public static boolean isOverworld(ClientPlayerEntity player) {
        return player.getWorld().getRegistryKey().equals(World.OVERWORLD);
    }

    public static String dimensionLabel(ClientPlayerEntity player) {
        var dim = player.getWorld().getRegistryKey();
        if (dim.equals(World.OVERWORLD)) return Text.translatable("explorermap.dimension.overworld").getString();
        if (dim.equals(World.NETHER))    return Text.translatable("explorermap.dimension.nether").getString();
        if (dim.equals(World.END))       return Text.translatable("explorermap.dimension.end").getString();
        String path = dim.getValue().getPath();
        return Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
    }
}
