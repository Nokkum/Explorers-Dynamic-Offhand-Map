package com.explorermap.mod.gui;

import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.network.ExpansionFailedPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * Holds the most recent expansion failure so FullMapScreen can render a
 * brief red flash / toast message near the direction button that failed.
 *
 * The message expires after DISPLAY_TICKS so it doesn't linger forever if
 * the player closes and reopens the map screen.
 */
@Environment(EnvType.CLIENT)
public final class ExpansionFeedback {

    private static final int DISPLAY_TICKS = 60; // ~3 seconds at 20 TPS

    private static ExpansionRecord.Direction failedDirection;
    private static ExpansionFailedPayload.Reason failedReason;
    private static long expiresAtMillis;

    private ExpansionFeedback() {}

    public static void reportFailure(ExpansionRecord.Direction direction,
                                      ExpansionFailedPayload.Reason reason) {
        failedDirection  = direction;
        failedReason     = reason;
        expiresAtMillis  = System.currentTimeMillis() + (DISPLAY_TICKS * 50L); // 50ms/tick
    }

    /** Returns the direction that most recently failed, or null if no active feedback. */
    public static ExpansionRecord.Direction getActiveFailureDirection() {
        if (System.currentTimeMillis() > expiresAtMillis) return null;
        return failedDirection;
    }

    /** Human-readable reason string for the active failure, or null. */
    public static String getActiveFailureMessage() {
        if (getActiveFailureDirection() == null) return null;
        String key = switch (failedReason) {
            case MISSING_PAPER            -> "explorermap.expansion.fail.missing_paper";
            case MISSING_INK_OR_COMPASS   -> "explorermap.expansion.fail.missing_ink_or_compass";
            case ALREADY_EXPANDED         -> "explorermap.expansion.fail.already_expanded";
            case NO_MAP_IN_OFFHAND        -> "explorermap.expansion.fail.no_map";
        };
        return Text.translatable(key).getString();
    }

    public static void clear() {
        failedDirection = null;
        expiresAtMillis = 0;
    }
}
