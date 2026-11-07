package com.explorermap.config;

import com.explorermap.ExplorerMapMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side settings, read from config/explorermap-server.json. Plain JSON on purpose: the server never
 * touches the Cloth Config based client settings, so Cloth Config is not needed on a dedicated server.
 */
public final class ExplorerMapServerConfig {

    public static final int MIN_EXPANSION_PAPER_COST = 1;
    public static final int MAX_EXPANSION_PAPER_COST = 8;

    private static volatile int expansionPaperCost = 1;

    private ExplorerMapServerConfig() {}

    public static int expansionPaperCost() {
        return expansionPaperCost;
    }

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("explorermap-server.json");
        if (Files.exists(file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (json.has("expansionPaperCost")) {
                    int cost = json.get("expansionPaperCost").getAsInt();
                    expansionPaperCost = Math.max(MIN_EXPANSION_PAPER_COST, Math.min(MAX_EXPANSION_PAPER_COST, cost));
                }
                return;
            } catch (IOException | RuntimeException e) {
                ExplorerMapMod.LOGGER.warn("[ExplorerMap] Could not read {}; using defaults", file, e);
                return;
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("expansionPaperCost", expansionPaperCost);
        try {
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch (IOException e) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] Could not write {}", file, e);
        }
    }
}
