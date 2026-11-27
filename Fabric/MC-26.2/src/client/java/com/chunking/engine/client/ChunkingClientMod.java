package com.chunking.engine.client;

import com.chunking.engine.ChunkingMod;
import com.chunking.engine.client.config.SettingsScreen;
import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.LiveStats;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Client entrypoint for settings UI, rebindable keybinds, and the live debug HUD.
 */
public final class ChunkingClientMod implements ClientModInitializer {
    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("chunking", "general"));

    @Override
    public void onInitializeClient() {
        if (Boolean.getBoolean("chunking.passive")) {
            return;
        }
        KeyMapping toggleHudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.chunking.toggle_hud", InputConstants.Type.KEYSYM, InputConstants.KEY_F7, KEY_CATEGORY));
        KeyMapping openSettingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.chunking.open_settings", InputConstants.Type.KEYSYM, InputConstants.KEY_F8, KEY_CATEGORY));
        KeyMapping resetHudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.chunking.reset_hud", InputConstants.Type.KEYSYM, InputConstants.KEY_F9, KEY_CATEGORY));

        DebugHud.setVisible(ModConfig.LIVE_BENCHMARK_HUD);
        DebugHud.setKeyMappings(toggleHudKey, resetHudKey);

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("chunking", "live_benchmark_hud"),
                DebugHud::render
        );

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof OptionsScreen) {
                Button btn = Button.builder(Component.literal("Chunking..."), b ->
                                client.gui.setScreen(SettingsScreen.create(screen)))
                        .bounds(Math.max(4, scaledWidth - 118), 6, 110, 20)
                        .build();
                Screens.getWidgets(screen).add(btn);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc == null || mc.gui == null) {
                return;
            }
            while (toggleHudKey.consumeClick()) {
                if (mc.level != null && mc.gui.screen() == null) {
                    DebugHud.toggleVisible();
                }
            }
            while (openSettingsKey.consumeClick()) {
                if (mc.gui.screen() == null) {
                    mc.gui.setScreen(SettingsScreen.create(null));
                }
            }
            while (resetHudKey.consumeClick()) {
                LiveStats.get().resetSession();
            }
        });

        ChunkingMod.LOGGER.info(
                "Chunking client initialized (liveHud={}, fullChunkRadius={})",
                ModConfig.LIVE_BENCHMARK_HUD,
                ModConfig.FULL_CHUNK_RADIUS
        );
    }
}
