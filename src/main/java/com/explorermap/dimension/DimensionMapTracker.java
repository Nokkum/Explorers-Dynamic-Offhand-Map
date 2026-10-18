package com.explorermap.dimension;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

@Environment(EnvType.CLIENT)
public final class DimensionMapTracker {

    private DimensionMapTracker() {}

    public static boolean isMapRelevantForCurrentDimension(LocalPlayer player, MapItemSavedData mapState) {
        return mapState.dimension.equals(player.level().dimension());
    }

    public static boolean isOverworld(LocalPlayer player) {
        return player.level().dimension().equals(Level.OVERWORLD);
    }

    public static String dimensionLabel(LocalPlayer player) {
        var dim = player.level().dimension();
        if (dim.equals(Level.OVERWORLD)) return Component.translatable("explorermap.dimension.overworld").getString();
        if (dim.equals(Level.NETHER))    return Component.translatable("explorermap.dimension.nether").getString();
        if (dim.equals(Level.END))       return Component.translatable("explorermap.dimension.end").getString();
        String path = dim.identifier().getPath();
        return Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
    }
}
