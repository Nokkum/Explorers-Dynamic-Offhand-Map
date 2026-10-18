package com.explorermap.api;

import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

@Environment(EnvType.CLIENT)
public final class ExplorerMapApi {

    @FunctionalInterface
    public interface BiomeColorProvider {
        OptionalInt color(ClientLevel world, BlockPos samplePos,
                          Holder<Biome> biome, int vanillaColor);
    }

    private static final Map<Identifier, BiomeColorProvider> BIOME_COLORS = new HashMap<>();
    private static long generation;

    private ExplorerMapApi() {}

    public static void registerBiomeColor(Identifier biomeId, BiomeColorProvider provider) {
        if (biomeId == null || provider == null) {
            throw new IllegalArgumentException("Biome id and provider are required");
        }
        BIOME_COLORS.put(biomeId, provider);
        generation++;
    }

    public static void registerWaypointIcon(String iconId, Identifier texture) {
        ExplorerMapRegistry.register(iconId, texture);
    }

    public static long generation() {
        return generation;
    }

    public static int colorFor(ClientLevel world, BlockPos samplePos, int vanillaColor) {
        var biomes = world.registryAccess().lookupOrThrow(Registries.BIOME);
        Holder<Biome> biome = world.getBiome(samplePos);
        Identifier biomeId = biomes.getKey(biome.value());
        if (biomeId == null) return vanillaColor;

        BiomeColorProvider provider = BIOME_COLORS.get(biomeId);
        if (provider == null) return vanillaColor;
        return provider.color(world, samplePos, biome, vanillaColor).orElse(vanillaColor);
    }
}