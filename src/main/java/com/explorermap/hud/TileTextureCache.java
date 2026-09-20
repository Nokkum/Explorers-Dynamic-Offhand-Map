package com.explorermap.hud;

import com.explorermap.data.MapEntryData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.item.map.MapState;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class TileTextureCache {

    private static final int FOG_ABGR = abgr(0xFF, 0x0C, 0x0C, 0x0C);

    private static final TileTextureCache INSTANCE = new TileTextureCache();
    public static TileTextureCache getInstance() { return INSTANCE; }

    private final Map<Integer, Entry> entries = new HashMap<>();
    private int nextSlot = 0;

    private TileTextureCache() {}

    public Identifier getOrUpdate(int mapId, MapState mapState, MapEntryData mapEntry) {
        Entry entry = entries.get(mapId);
        long gen = mapEntry.getDiscoveryGeneration();

        if (entry == null) {
            entry = new Entry(nextSlot++);
            entries.put(mapId, entry);
        }

        if (entry.generation != gen) {
            rebuild(entry, mapState, mapEntry);
            entry.generation = gen;
        }

        return entry.identifier;
    }

    public void clearAll() {
        var tm = MinecraftClient.getInstance().getTextureManager();
        for (Entry e : entries.values()) {
            if (e.texture != null) {
                tm.destroyTexture(e.identifier);
                e.texture.close();
                e.texture = null;
            }
        }
        entries.clear();
        nextSlot = 0;
    }

    private void rebuild(Entry entry, MapState mapState, MapEntryData mapEntry) {
        byte[] colors  = mapState.colors;
        byte[] bitmask = mapEntry.getDiscoveredPixelsCopy();

        var tm = MinecraftClient.getInstance().getTextureManager();

        NativeImage target;
        if (entry.texture == null) {
            target = new NativeImage(NativeImage.Format.RGBA, 128, 128, false);
        } else {
            NativeImage texImage = entry.texture.getImage();
            if (texImage != null) {
                target = texImage;
            } else {
                target = new NativeImage(NativeImage.Format.RGBA, 128, 128, false);
                tm.destroyTexture(entry.identifier);
                entry.texture.close();
                entry.texture = null;
            }
        }

        for (int row = 0; row < 128; row++) {
            for (int col = 0; col < 128; col++) {
                int bit = row * 128 + col;
                boolean discovered = (bitmask[bit >> 3] & (1 << (bit & 7))) != 0;

                int abgr;
                if (discovered) {
                    int argb = MapColor.getRenderColor(colors[row * 128 + col] & 0xFF) | 0xFF000000;
                    abgr = argbToAbgr(argb);
                } else {
                    abgr = FOG_ABGR;
                }
                target.setColor(col, row, abgr);
            }
        }

        if (entry.texture == null) {
            entry.texture    = new NativeImageBackedTexture(target);
            entry.identifier = tm.registerDynamicTexture("explorermap/tile/" + entry.slot, entry.texture);
        } else {
            entry.texture.upload();
        }
    }

    private static int argbToAbgr(int argb) {
        int a = (argb >> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int abgr(int a, int b, int g, int r) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static final class Entry {
        final int slot;
        long generation = Long.MIN_VALUE;
        NativeImageBackedTexture texture;
        Identifier identifier;

        Entry(int slot) { this.slot = slot; }
    }
}
