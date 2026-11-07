package com.explorermap.structure;

import java.util.Locale;
import java.util.regex.Pattern;

/** Maps registry structure ids to the types the mod knows about. Plain Java, so it is unit tested. */
public final class StructureIds {

    /** Automatic waypoint names are stored as translation keys under this prefix. */
    public static final String NAME_KEY_PREFIX = "explorermap.structure.";

    private static final int MAX_TYPE_LENGTH = 32;
    private static final Pattern COUNT_SUFFIX = Pattern.compile(" \\(\\d+\\)$");

    private StructureIds() {}

    /** Folds the variants vanilla registers per biome (village_plains, mineshaft_mesa, ...) into one type. */
    public static String normalize(String path) {
        if (path.equals("village") || path.startsWith("village_")) return "village";
        if (path.equals("mineshaft") || path.startsWith("mineshaft_")) return "mineshaft";
        if (path.equals("mansion")) return "woodland_mansion";
        return path;
    }

    public static String nameKey(String path) {
        String type = normalize(path);
        if (type.length() > MAX_TYPE_LENGTH) type = type.substring(0, MAX_TYPE_LENGTH);
        return NAME_KEY_PREFIX + type;
    }

    public static String icon(String path) {
        return switch (normalize(path)) {
            case "village" -> "explorermap:village";
            case "desert_pyramid", "jungle_pyramid" -> "explorermap:temple";
            case "stronghold", "ancient_city", "pillager_outpost", "woodland_mansion", "mineshaft" -> "explorermap:dungeon";
            default -> "explorermap:pin";
        };
    }

    public static int color(String path) {
        return switch (normalize(path)) {
            case "village" -> 0xFFFFFF55;
            case "desert_pyramid", "jungle_pyramid" -> 0xFFFF8800;
            case "stronghold" -> 0xFFAA55FF;
            case "ancient_city" -> 0xFF55FFFF;
            case "woodland_mansion", "pillager_outpost" -> 0xFFFF5555;
            default -> 0xFFFFFFFF;
        };
    }

    /**
     * The structure type behind a stored waypoint name, ignoring a " (2)" style suffix. Names written by
     * older versions were English text ("Village Plains"), so those map to the same type.
     */
    public static String typeOfStoredName(String storedName) {
        String name = COUNT_SUFFIX.matcher(storedName).replaceFirst("");
        if (name.startsWith(NAME_KEY_PREFIX)) return normalize(name.substring(NAME_KEY_PREFIX.length()));
        return normalize(name.toLowerCase(Locale.ROOT).replace(' ', '_'));
    }
}
