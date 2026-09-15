package com.explorermap.mod.registry;

import com.explorermap.mod.ExplorerMapMod;
import net.minecraft.util.Identifier;

public class ExplorerMapRegistry {

    public static Identifier id(String path) {
        return Identifier.of(ExplorerMapMod.MOD_ID, path);
    }

    private static final java.util.Map<String, Identifier> WAYPOINT_ICONS = new java.util.LinkedHashMap<>();

    static {

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
