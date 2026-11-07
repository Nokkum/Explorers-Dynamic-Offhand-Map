package com.explorermap.gui;

import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Translatable text shared by the screens. */
@Environment(EnvType.CLIENT)
public final class GuiText {

    /** Names of automatic structure waypoints are stored as translation keys, so each player sees their language. */
    public static final String STRUCTURE_KEY_PREFIX = "explorermap.structure.";

    private GuiText() {}

    public static Component expandLabel(ExpansionRecord.Direction direction, boolean expanded) {
        return Component.translatable("label.explorermap.expand." + (expanded ? "done." : "")
                + direction.name().toLowerCase(Locale.ROOT));
    }

    public static Component hdToggle(boolean on) {
        return Component.translatable(on ? "label.explorermap.hd.on" : "label.explorermap.hd.off");
    }

    public static Component expansionMode(boolean highDetail) {
        return Component.translatable(highDetail
                ? "explorermap.expansion.mode.hd" : "explorermap.expansion.mode.standard");
    }

    public static String waypointName(Waypoint waypoint) {
        return displayName(waypoint.name());
    }

    /** Turns a stored name (plain text, or a structure key with an optional " (2)" suffix) into display text. */
    public static String displayName(String stored) {
        if (stored == null) return "";
        if (!stored.startsWith(STRUCTURE_KEY_PREFIX)) return stored;

        int space = stored.indexOf(' ');
        String key = space < 0 ? stored : stored.substring(0, space);
        String suffix = space < 0 ? "" : stored.substring(space);

        String translated = Component.translatable(key).getString();
        if (translated.equals(key)) {
            // Modded structure without a translation: fall back to a readable form of its id.
            translated = titleCase(key.substring(STRUCTURE_KEY_PREFIX.length()));
        }
        return translated + suffix;
    }

    private static String titleCase(String id) {
        StringBuilder out = new StringBuilder(id.length());
        boolean upper = true;
        for (char c : id.toCharArray()) {
            if (c == '_' || c == '/') {
                out.append(' ');
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }
}
