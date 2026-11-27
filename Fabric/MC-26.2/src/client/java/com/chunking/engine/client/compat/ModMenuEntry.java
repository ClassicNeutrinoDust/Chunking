package com.chunking.engine.client.compat;

import com.chunking.engine.client.config.SettingsScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Optional ModMenu entrypoint kept isolated so Chunking loads cleanly when ModMenu is absent.
 */
public final class ModMenuEntry implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::create;
    }
}
