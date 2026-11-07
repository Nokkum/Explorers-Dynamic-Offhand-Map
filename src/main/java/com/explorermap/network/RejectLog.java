package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.MapIdentity;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Logs rejected client packets: throttled per player, with client-controlled text sanitized. */
public final class RejectLog {

    private static final long INTERVAL_NANOS = 10_000_000_000L;
    private static final int MAX_TEXT = 48;
    private static final Map<UUID, Long> LAST_WARNED = new ConcurrentHashMap<>();

    private RejectLog() {}

    public static void warn(ServerPlayer player, String message, Object... args) {
        Object[] clean = new Object[args.length];
        for (int i = 0; i < args.length; i++) clean[i] = sanitize(args[i]);

        long now = System.nanoTime();
        Long last = LAST_WARNED.get(player.getUUID());
        String full = "[ExplorerMap] Rejected packet from " + clean(player.getName().getString()) + ": " + message;
        if (last != null && now - last < INTERVAL_NANOS) {
            ExplorerMapMod.LOGGER.debug(full, clean);
            return;
        }
        LAST_WARNED.put(player.getUUID(), now);
        ExplorerMapMod.LOGGER.warn(full, clean);
    }

    public static void forget(UUID playerId) {
        LAST_WARNED.remove(playerId);
    }

    private static Object sanitize(Object value) {
        if (value instanceof MapIdentity id) return clean(id.asKey());
        if (value instanceof String s) return clean(s);
        return value;
    }

    private static String clean(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_TEXT));
        for (int i = 0; i < text.length() && out.length() < MAX_TEXT; i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? '?' : c);
        }
        if (text.length() > MAX_TEXT) out.append("...");
        return out.toString();
    }
}
