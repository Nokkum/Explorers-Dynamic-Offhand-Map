package com.explorermap.hud;

import com.explorermap.ExplorerMapMod;
import com.explorermap.api.ExplorerMapApi;
import com.explorermap.config.ExplorerMapConfig;
import com.explorermap.data.MapEntryData;
import com.explorermap.data.MapIdentity;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.HashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class TileTextureCache {

    private static final int FOG_ARGB = 0xFF0C0C0C;

    private static final TileTextureCache INSTANCE = new TileTextureCache();
    public static TileTextureCache getInstance() { return INSTANCE; }

    private final Map<MapIdentity, Entry> entries = new HashMap<>();
    private int nextSlot = 0;

    private TileTextureCache() {}

    public Identifier getOrUpdate(MapIdentity mapId, MapItemSavedData mapState, MapEntryData mapEntry) {
        Entry entry = entries.get(mapId);
        long gen = mapEntry.getVisualGeneration();
        boolean highlightRecent = ExplorerMapConfig.get().highlightRecentDiscoveries;
        long integrationGeneration = ExplorerMapApi.generation();

        if (entry == null) {
            entry = new Entry(nextSlot++);
            entries.put(mapId, entry);
        }

        if (entry.generation != gen || entry.highlightRecent != highlightRecent
                || entry.integrationGeneration != integrationGeneration) {
            rebuild(entry, mapState, mapEntry);
            entry.generation = gen;
            entry.highlightRecent = highlightRecent;
            entry.integrationGeneration = integrationGeneration;
        }

        return entry.identifier;
    }

    public void clearAll() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (Entry e : entries.values()) {
            if (e.texture != null) {
                // release() unregisters the texture and closes it (and its NativeImage).
                tm.release(e.identifier);
                e.texture = null;
            }
        }
        entries.clear();
        nextSlot = 0;
    }

    private void rebuild(Entry entry, MapItemSavedData mapState, MapEntryData mapEntry) {
        byte[] colors  = mapState.colors;
        byte[] bitmask = mapEntry.getDiscoveredPixelsCopy();
        var world = Minecraft.getInstance().level;
        int scale = 1 << mapState.scale;
        int minX = mapState.centerX - 64 * scale;
        int minZ = mapState.centerZ - 64 * scale;
        boolean sameDimension = world != null && world.dimension().equals(mapState.dimension);

        var tm = Minecraft.getInstance().getTextureManager();

        NativeImage target;
        if (entry.texture == null) {
            target = new NativeImage(NativeImage.Format.RGBA, 128, 128, false);
        } else {
            NativeImage texImage = entry.texture.getPixels();
            if (texImage != null) {
                target = texImage;
            } else {
                target = new NativeImage(NativeImage.Format.RGBA, 128, 128, false);
                tm.release(entry.identifier);
                entry.texture = null;
            }
        }

        for (int row = 0; row < 128; row++) {
            for (int col = 0; col < 128; col++) {
                int bit = row * 128 + col;
                boolean discovered = (bitmask[bit >> 3] & (1 << (bit & 7))) != 0;

                int argb;
                if (discovered) {
                    argb = MapColor.getColorFromPackedId(colors[row * 128 + col] & 0xFF);
                    if (sameDimension) {
                        int sampleX = minX + col * scale + scale / 2;
                        int sampleZ = minZ + row * scale + scale / 2;
                        int sampleY = surfaceY(world, sampleX, sampleZ);
                        var sample = new BlockPos(sampleX, sampleY, sampleZ);
                        argb = ExplorerMapApi.colorFor(world, sample, argb);
                    }
                    argb |= 0xFF000000;
                    if (ExplorerMapConfig.get().highlightRecentDiscoveries) {
                        argb = highlightRecent(argb, mapEntry.recentValue(col, row));
                    }
                } else {
                    argb = FOG_ARGB;
                }
                // setPixel() takes ARGB (setPixelABGR() is the ABGR variant).
                // If map colours ever look red/blue swapped, switch this call to setPixelABGR.
                target.setPixel(col, row, argb);
            }
        }

        if (entry.texture == null) {
            Identifier id = Identifier.fromNamespaceAndPath(ExplorerMapMod.MOD_ID, "tile/" + entry.slot);
            int slot = entry.slot;
            entry.texture    = new DynamicTexture(() -> "explorermap tile " + slot, target);
            entry.identifier = id;
            tm.register(id, entry.texture);
        } else {
            entry.texture.upload();
        }
    }

    private static int surfaceY(Level world, int x, int z) {
        if (world == null) return 64;
        try {
            int topY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            return Math.max(world.getMinY(), topY - 1);
        } catch (Exception e) {
            return 64;
        }
    }

    private static int highlightRecent(int argb, int recency) {
        if (recency <= 0) return argb;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int boost = 24 + (recency * 40 / 255);
        r = Math.min(255, r + boost);
        g = Math.min(255, g + boost);
        b = Math.min(255, b + boost / 2);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static final class Entry {
        final int slot;
        long generation = Long.MIN_VALUE;
        long integrationGeneration = Long.MIN_VALUE;
        boolean highlightRecent = true;
        DynamicTexture texture;
        Identifier identifier;

        Entry(int slot) { this.slot = slot; }
    }
}
