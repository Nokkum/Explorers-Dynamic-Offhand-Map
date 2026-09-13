package com.explorermap.mod;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.autoconfig.AutoConfig;
import com.explorermap.mod.config.ExplorerMapConfig;

/**
 * Registers the Cloth Config screen with Mod Menu so players can access
 * settings from the mods list without needing to edit JSON files.
 *
 * This class is only loaded if Mod Menu is present (listed under "suggests"
 * in fabric.mod.json, not "depends").
 */
public class ExplorerMapModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> AutoConfig.getConfigScreen(ExplorerMapConfig.class, parent).get();
    }
}
