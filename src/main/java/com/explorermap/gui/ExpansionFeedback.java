package com.explorermap.gui;

import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.network.ExpansionFailedPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public final class ExpansionFeedback {

    private static final int DISPLAY_TICKS = 60;

    private static ExpansionRecord.Direction failedDirection;
    private static ExpansionFailedPayload.Reason failedReason;
    private static long expiresAtMillis;

    private ExpansionFeedback() {}

    public static void reportFailure(ExpansionRecord.Direction direction,
                                      ExpansionFailedPayload.Reason reason) {
        failedDirection  = direction;
        failedReason     = reason;
        expiresAtMillis  = System.currentTimeMillis() + (DISPLAY_TICKS * 50L);
    }

    public static ExpansionRecord.Direction getActiveFailureDirection() {
        if (System.currentTimeMillis() > expiresAtMillis) return null;
        return failedDirection;
    }

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
