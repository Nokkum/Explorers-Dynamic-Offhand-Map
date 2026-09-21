package com.explorermap.config;

import com.explorermap.ExplorerMapMod;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

@Config(name = ExplorerMapMod.MOD_ID)
public class ExplorerMapConfig implements ConfigData {

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

    @ConfigEntry.Gui.Tooltip
    public boolean showHud = true;

    @ConfigEntry.Gui.Tooltip
    public Corner corner = Corner.BOTTOM_RIGHT;

    @ConfigEntry.BoundedDiscrete(min = 60, max = 240)
    public int mapSize = 100;

    @ConfigEntry.BoundedDiscrete(min = 2, max = 32)
    public int padding = 8;

    @ConfigEntry.BoundedDiscrete(min = 20, max = 100)
    public int opacityPercent = 90;

    public float opacity() { return opacityPercent / 100f; }

    @ConfigEntry.Gui.Tooltip
    public boolean hideWhenSneaking = false;

    @ConfigEntry.Gui.Tooltip
    public boolean hideInMenus = true;

    @ConfigEntry.Gui.Tooltip
    public boolean fogOfDiscovery = true;

    @ConfigEntry.Gui.Tooltip
    public boolean highlightRecentDiscoveries = true;

    @ConfigEntry.BoundedDiscrete(min = 1, max = 20)
    public int discoveryTickInterval = 3;

    @ConfigEntry.BoundedDiscrete(min = 8, max = 64)
    public int rayCount = 32;

    @ConfigEntry.Gui.Tooltip
    public boolean showExpansionArrows = true;

    @ConfigEntry.BoundedDiscrete(min = 1, max = 8)
    public int expansionPaperCost = 1;

    @ConfigEntry.Gui.Tooltip
    public boolean showCompass = true;

    @ConfigEntry.Gui.Tooltip
    public boolean showCoordinates = true;

    @ConfigEntry.Gui.Tooltip
    public boolean showGridLines = false;

    @ConfigEntry.Gui.Tooltip
    public GridSpacing gridSpacing = GridSpacing.CHUNK;

    public enum GridSpacing {
        CHUNK, FOUR_CHUNKS
    }

    public enum Corner {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }
}
