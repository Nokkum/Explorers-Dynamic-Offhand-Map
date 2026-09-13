package com.explorermap.mod.registry;

import com.explorermap.mod.ExplorerMapMod;
import net.minecraft.util.Identifier;

/**
 * Central registry utilities for Explorer Map.
 *
 * Also acts as the waypoint icon registry — mods can call
 * ExplorerMapRegistry.registerWaypointIcon() to add their structure POIs.
 */
public class ExplorerMapRegistry {

    /** Creates a namespaced Identifier under the explorermap namespace. */
    public static Identifier id(String path) {
        return Identifier.of(ExplorerMapMod.MOD_ID, path);
    }

    // ── Waypoint Icon Registry ─────────────────────────────────────────────
    // Key: icon ID string (e.g. "explorermap:village")
    // Value: ResourceLocation pointing to a 16×16 texture in assets/explorermap/textures/waypoints/

    private static final java.util.Map<String, Identifier> WAYPOINT_ICONS = new java.util.LinkedHashMap<>();

    static {
        // Built-in icons
        register("explorermap:pin",      id("textures/waypoints/pin.png"));
        register("explorermap:village",  id("textures/waypoints/village.png"));
        register("explorermap:temple",   id("textures/waypoints/temple.png"));
        register("explorermap:dungeon",  id("textures/waypoints/dungeon.png"));
        register("explorermap:base",     id("textures/waypoints/base.png"));
    }

    public static void register(String iconId, Identifier texture) {
        WAYPOINT_ICONS.put(iconId, texture);
    }

    public static Identifier getWaypointTexture(String iconId) {
        return WAYPOINT_ICONS.getOrDefault(iconId, WAYPOINT_ICONS.get("explorermap:pin"));
    }

    public static java.util.Set<String> allIconIds() {
        return java.util.Collections.unmodifiableSet(WAYPOINT_ICONS.keySet());
    }
}
