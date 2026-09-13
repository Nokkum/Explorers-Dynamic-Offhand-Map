package com.explorermap.mod.config;

import com.explorermap.mod.ExplorerMapMod;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

/**
 * All player-facing configuration for Explorer's Dynamic Off-Hand Map.
 *
 * Uses Cloth Config's AutoConfig for automatic screen generation
 * (integrated with Mod Menu).
 *
 * Access the singleton via ExplorerMapConfig.get().
 * Persist changes by calling ExplorerMapConfig.save().
 */
@Config(name = ExplorerMapMod.MOD_ID)
public class ExplorerMapConfig implements ConfigData {

    // ── Singleton ─────────────────────────────────────────────────────────

    private static ExplorerMapConfig INSTANCE;

    public static void load() {
        AutoConfig.register(ExplorerMapConfig.class, GsonConfigSerializer::new);
        INSTANCE = AutoConfig.getConfigHolder(ExplorerMapConfig.class).getConfig();
    }

    public static ExplorerMapConfig get() {
        return INSTANCE != null ? INSTANCE : new ExplorerMapConfig();
    }

    public static void save() {
        if (INSTANCE != null)
            AutoConfig.getConfigHolder(ExplorerMapConfig.class).save();
    }

    // ── HUD display ───────────────────────────────────────────────────────

    @ConfigEntry.Gui.Tooltip
    public boolean showHud = true;

    @ConfigEntry.Gui.Tooltip
    public Corner corner = Corner.BOTTOM_RIGHT;

    /** Rendered size of the mini-map in screen pixels (before GUI scaling). */
    @ConfigEntry.BoundedDiscrete(min = 60, max = 240)
    public int mapSize = 100;

    /** Padding from screen edge in pixels. */
    @ConfigEntry.BoundedDiscrete(min = 2, max = 32)
    public int padding = 8;

    /** Overall HUD opacity (0.0 – 1.0). */
    @ConfigEntry.BoundedDiscrete(min = 20, max = 100)
    public int opacityPercent = 90; // stored as integer for config screen slider

    public float opacity() { return opacityPercent / 100f; }

    // workaround: Cloth Config needs a direct float field for some accessors
    public float opacity = 0.9f;

    // ── Visibility toggles ────────────────────────────────────────────────

    @ConfigEntry.Gui.Tooltip
    public boolean hideWhenSneaking = false;

    @ConfigEntry.Gui.Tooltip
    public boolean hideInMenus = true;

    // ── Exploration engine ────────────────────────────────────────────────

    @ConfigEntry.Gui.Tooltip
    public boolean fogOfDiscovery = true;

    /** How many ticks between discovery updates (lower = more accurate but more CPU). */
    @ConfigEntry.BoundedDiscrete(min = 1, max = 20)
    public int discoveryTickInterval = 3;

    /** Number of horizontal rays cast per update. */
    @ConfigEntry.BoundedDiscrete(min = 8, max = 64)
    public int rayCount = 32;

    // ── Expansion ─────────────────────────────────────────────────────────

    @ConfigEntry.Gui.Tooltip
    public boolean showExpansionArrows = true;

    /** Resource cost for standard expansion: Paper quantity. */
    @ConfigEntry.BoundedDiscrete(min = 1, max = 8)
    public int expansionPaperCost = 1;

    // ── Compass overlay ───────────────────────────────────────────────────

    @ConfigEntry.Gui.Tooltip
    public boolean showCompass = true;

    // ── Corner enum ───────────────────────────────────────────────────────

    public enum Corner {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }
}
