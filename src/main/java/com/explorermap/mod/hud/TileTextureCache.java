package com.explorermap.mod.hud;

import com.explorermap.mod.attachment.MapDiscoveryAttachment;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.DynamicTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;
import net.minecraft.world.storage.MapState;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-tile GPU texture cache for the explorer mini-map.
 *
 * Problem solved
 * ──────────────
 * The old renderer called DrawContext.fill() once per pixel per frame —
 * up to 16 384 calls per tile at 60 fps. With 4 tiles that is nearly
 * 4 million Java-to-native calls per second, causing measurable frame
 * drops on typical hardware.
 *
 * Solution
 * ────────
 * For each (mapId, dimensionId) pair we maintain one 128×128 RGBA
 * NativeImage and one DynamicTexture registered with the TextureManager.
 * When the underlying map data changes (new pixel discovered, or first
 * access), we rebuild the NativeImage in one tight loop and upload it to
 * the GPU. Rendering is then a single drawTexture call per tile.
 *
 * Dirty tracking
 * ──────────────
 * Each entry stores a "generation" counter. MapDiscoveryAttachment
 * exposes getDiscoveryGeneration() which increments every time discover()
 * is called. When the cached generation differs from the attachment's,
 * we rebuild. This means the texture is rebuilt at most once per
 * discoveryTickInterval ticks, not every frame.
 *
 * Lifecycle
 * ─────────
 * Textures are created on first use and closed on eviction or mod unload.
 * clearAll() should be called from the client disconnect event to release
 * GPU memory between sessions.
 */
@Environment(EnvType.CLIENT)
public final class TileTextureCache {

    /** Fog color for undiscovered pixels: near-black, fully opaque. */
    private static final int FOG_ABGR = abgr(0xFF, 0x0C, 0x0C, 0x0C);

    // Singleton — one cache for the whole client session.
    private static final TileTextureCache INSTANCE = new TileTextureCache();
    public static TileTextureCache getInstance() { return INSTANCE; }

    private final Map<Integer, Entry> entries = new HashMap<>();
    private int nextId = 0;

    private TileTextureCache() {}

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns the Identifier of the GPU texture for this tile, uploading
     * a fresh image if the discovery data has changed since last frame.
     *
     * @param mapId      Vanilla integer map ID (key for caching).
     * @param mapState   MapState carrying the vanilla color palette.
     * @param attachment MapDiscoveryAttachment carrying the discovery bitmask.
     * @return Identifier suitable for DrawContext.drawTexture().
     */
    public Identifier getOrUpdate(int mapId, MapState mapState,
                                   MapDiscoveryAttachment attachment) {
        Entry entry = entries.get(mapId);
        long gen = attachment.getDiscoveryGeneration();

        if (entry == null) {
            entry = new Entry(nextId++);
            entries.put(mapId, entry);
        }

        if (entry.generation != gen) {
            rebuild(entry, mapState, attachment);
            entry.generation = gen;
        }

        return entry.identifier;
    }

    /**
     * Forces a full rebuild of the texture for the given map ID on the
     * next call to getOrUpdate(). Called when an expansion is granted
     * and a new tile comes online.
     */
    public void invalidate(int mapId) {
        Entry entry = entries.get(mapId);
        if (entry != null) entry.generation = Long.MIN_VALUE;
    }

    /**
     * Releases all GPU textures. Call on client disconnect or mod unload.
     */
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
        nextId = 0;
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private void rebuild(Entry entry, MapState mapState,
                          MapDiscoveryAttachment attachment) {
        byte[] colors   = mapState.colors;
        byte[] bitmask  = attachment.getDiscoveredPixelsCopy();

        // Allocate or reuse the NativeImage
        if (entry.image == null) {
            entry.image = new NativeImage(NativeImage.Format.RGBA, 128, 128, false);
        }

        for (int row = 0; row < 128; row++) {
            for (int col = 0; col < 128; col++) {
                int bit   = row * 128 + col;
                boolean discovered = (bitmask[bit >> 3] & (1 << (bit & 7))) != 0;

                int abgr;
                if (discovered) {
                    int argb = MapColor.getRenderColor(colors[row * 128 + col] & 0xFF)
                               | 0xFF000000;
                    abgr = argbToAbgr(argb);
                } else {
                    abgr = FOG_ABGR;
                }

                entry.image.setColor(col, row, abgr);
            }
        }

        // Upload to GPU
        var tm = MinecraftClient.getInstance().getTextureManager();
        if (entry.texture == null) {
            // First time: create the DynamicTexture from the NativeImage and register it.
            // DynamicTexture takes ownership of the image on construction.
            entry.texture    = new DynamicTexture(entry.image);
            entry.identifier = tm.registerDynamicTexture(
                    "explorermap/tile/" + entry.slot, entry.texture);
        } else {
            // Subsequent updates: overwrite the image pixels in-place and re-upload.
            // We write directly into the texture's own NativeImage via getImage(),
            // then call upload(). This avoids setImage() which was removed in 1.21.
            NativeImage texImage = entry.texture.getImage();
            if (texImage != null) {
                for (int row = 0; row < 128; row++) {
                    for (int col = 0; col < 128; col++) {
                        texImage.setColor(col, row, entry.image.getColor(col, row));
                    }
                }
                entry.texture.upload();
            } else {
                // Fallback: destroy and re-create if getImage() returns null
                tm.destroyTexture(entry.identifier);
                entry.texture.close();
                entry.texture    = new DynamicTexture(entry.image);
                entry.identifier = tm.registerDynamicTexture(
                        "explorermap/tile/" + entry.slot, entry.texture);
            }
        }
    }

    // ── Color conversion ──────────────────────────────────────────────────

    /**
     * NativeImage uses ABGR byte order internally (little-endian RGBA).
     * DrawColor is ARGB. This converts between them.
     */
    private static int argbToAbgr(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int abgr(int a, int b, int g, int r) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    // ── Cache entry ───────────────────────────────────────────────────────

    private static final class Entry {
        final int slot;
        long          generation = Long.MIN_VALUE;
        NativeImage   image;
        DynamicTexture texture;
        Identifier    identifier;

        Entry(int slot) {
            this.slot = slot;
        }
    }
}
