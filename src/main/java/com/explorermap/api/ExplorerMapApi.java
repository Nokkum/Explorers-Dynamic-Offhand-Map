package com.explorermap.api;

import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

@Environment(EnvType.CLIENT)
public final class ExplorerMapApi {

    @FunctionalInterface
    public interface BiomeColorProvider {
        OptionalInt color(ClientWorld world, BlockPos samplePos,
                          RegistryEntry<Biome> biome, int vanillaColor);
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

    public static int colorFor(ClientWorld world, BlockPos samplePos, int vanillaColor) {
        var biomes = world.getRegistryManager().get(RegistryKeys.BIOME);
        RegistryEntry<Biome> biome = world.getBiome(samplePos);
        Identifier biomeId = biomes.getKey(biome.value()).map(key -> key.getValue()).orElse(null);
        if (biomeId == null) return vanillaColor;

        BiomeColorProvider provider = BIOME_COLORS.get(biomeId);
        if (provider == null) return vanillaColor;
        return provider.color(world, samplePos, biome, vanillaColor).orElse(vanillaColor);
    }
}