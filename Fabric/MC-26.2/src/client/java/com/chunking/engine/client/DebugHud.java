package com.chunking.engine.client;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
import com.chunking.engine.core.LiveStats;
import com.chunking.engine.core.StageTimer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Live in-game benchmark HUD (toggled with F7, moving-benchmark reset with F9).
 * Displays FPS, 1% low, 0.1% low, MSPT, live CPS across client loads / server sends / features / noise / surface / biomes,
 * and moving-only average + peak CPS using a reusable {@link StringBuilder} for near-zero render-thread allocation.
 */
public final class DebugHud {
    private static final long HUD_FORMAT_INTERVAL_NS = 250_000_000L;
    private static final StringBuilder SB = new StringBuilder(160);
    private static volatile boolean visible = ModConfig.LIVE_BENCHMARK_HUD;
    private static net.minecraft.client.KeyMapping toggleKeyMapping;
    private static net.minecraft.client.KeyMapping resetKeyMapping;
    private static long lastHudFormatNs = 0L;
    private static String l1 = "", l2 = "", l3 = "", l4 = "", l5 = "", l6 = "", l7 = "";
    private static int cachedMaxW = 0;
    private static boolean lastHighMspt = false;

    private DebugHud() {}

    public static void setVisible(boolean show) {
        visible = show;
    }

    public static void toggleVisible() {
        visible = !visible;
    }

    public static void setKeyMappings(
            net.minecraft.client.KeyMapping toggle,
            net.minecraft.client.KeyMapping reset
    ) {
        toggleKeyMapping = toggle;
        resetKeyMapping = reset;
    }

    public static void resetDisplay() {
        lastHudFormatNs = 0L;
        cachedMaxW = 0;
    }

    public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.options == null || mc.font == null
                || (mc.gui != null && mc.gui.screen() != null)) {
            return;
        }

        if (mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        Font font = mc.font;
        long now = System.nanoTime();
        if (now - lastHudFormatNs >= HUD_FORMAT_INTERVAL_NS || cachedMaxW == 0) {
            lastHudFormatNs = now;
            LiveStats.Snapshot s = LiveStats.get().sampleSnapshot();
            StageTimer prof = ChunkEngine.get().stageProfiler();
            lastHighMspt = s.msptMs > 30.0D;

            StringBuilder sb = SB;
            String hideKey = toggleKeyMapping != null ? toggleKeyMapping.getTranslatedKeyMessage().getString() : "F7";
            String resetKey = resetKeyMapping != null ? resetKeyMapping.getTranslatedKeyMessage().getString() : "F9";

            sb.setLength(0);
            sb.append("Chunking Live Bench [").append(s.workers).append("W | ")
                    .append(s.govLevel).append("]  (").append(hideKey).append(": Hide  ")
                    .append(resetKey).append(": Reset)");
            l1 = sb.toString();

            sb.setLength(0);
            sb.append("FPS: ");
            appendRoundedInt(sb, s.fpsNow);
            sb.append("  Avg: ");
            appendRoundedInt(sb, s.fpsAvg);
            sb.append("  1% Low: ");
            appendRoundedInt(sb, s.fps1PctLow);
            sb.append("  0.1% Low: ");
            appendRoundedInt(sb, s.fps01PctLow);
            sb.append("  (p99: ");
            appendFixed1(sb, s.frameP99Ms);
            sb.append("ms)");
            l2 = sb.toString();

            sb.setLength(0);
            sb.append("MSPT: ");
            appendFixed1(sb, s.msptMs);
            sb.append(" ms (pk ");
            appendFixed1(sb, s.msptPeakMs);
            sb.append(") | Speed: ");
            appendFixed1(sb, s.speedBlocksPerSec);
            sb.append(" blk/s ").append(s.isMoving ? "[MOVING]" : "[IDLE]");
            l3 = sb.toString();

            sb.setLength(0);
            sb.append("Live CPS -> Load: ");
            appendRoundedInt(sb, s.clientLoadCps);
            sb.append("/s | Send: ");
            appendRoundedInt(sb, s.serverSendCps);
            sb.append("/s | Feat: ");
            appendRoundedInt(sb, s.featuresCps);
            sb.append("/s | Noise: ");
            appendRoundedInt(sb, s.noiseCps);
            sb.append("/s");
            l4 = sb.toString();

            sb.setLength(0);
            sb.append("Stage CPS -> Surf: ");
            appendRoundedInt(sb, s.surfaceCps);
            sb.append("/s | Bio: ");
            appendRoundedInt(sb, s.biomesCps);
            sb.append("/s | Struct: ");
            appendRoundedInt(sb, s.structureCps);
            sb.append("/s");
            l5 = sb.toString();

            sb.setLength(0);
            sb.append("Moving CPS -> Load Avg: ");
            appendFixed1(sb, s.movingAvgLoadCps);
            sb.append(" (Peak ");
            appendRoundedInt(sb, s.movingPeakLoadCps);
            sb.append(") | Noise Avg: ");
            appendFixed1(sb, s.movingAvgNoiseCps);
            sb.append(" (Peak ");
            appendRoundedInt(sb, s.movingPeakNoiseCps);
            sb.append(')');
            l6 = sb.toString();

            sb.setLength(0);
            sb.append("Stage ms -> Bio: ");
            appendFixed2(sb, prof.averageMillis(StageTimer.Stage.BIOMES));
            sb.append(" | Noise: ");
            appendFixed2(sb, prof.averageMillis(StageTimer.Stage.NOISE));
            sb.append(" | Surf: ");
            appendFixed2(sb, prof.averageMillis(StageTimer.Stage.SURFACE));
            sb.append(" | Feat: ");
            appendFixed2(sb, prof.averageMillis(StageTimer.Stage.FEATURES));
            sb.append(" | Active: ").append(s.activeUpstream).append('U')
                    .append('+').append(s.activeFeatures).append("F (Q:")
                    .append(s.pendingFeatures + s.poolQueueDepth).append(')');
            l7 = sb.toString();

            cachedMaxW = Math.max(font.width(l1),
                    Math.max(font.width(l2),
                    Math.max(font.width(l3),
                    Math.max(font.width(l4),
                    Math.max(font.width(l5),
                    Math.max(font.width(l6), font.width(l7)))))));
        }

        int x = 6;
        int y = 6;
        int pad = 4;
        int lineH = 10;
        int boxW = cachedMaxW + pad * 2;
        int boxH = lineH * 7 + pad * 2;

        graphics.fill(x, y, x + boxW, y + boxH, 0xB410141C);
        graphics.fill(x, y, x + boxW, y + 1, 0xFF38BDF8);

        int tx = x + pad;
        int ty = y + pad;
        graphics.text(font, l1, tx, ty, 0xFF38BDF8, true);
        graphics.text(font, l2, tx, ty + lineH, 0xFF4ADE80, true);
        graphics.text(font, l3, tx, ty + lineH * 2, lastHighMspt ? 0xFFFACC15 : 0xFFE2E8F0, true);
        graphics.text(font, l4, tx, ty + lineH * 3, 0xFFFDE047, true);
        graphics.text(font, l5, tx, ty + lineH * 4, 0xFFCBD5E1, true);
        graphics.text(font, l6, tx, ty + lineH * 5, 0xFF7DD3FC, true);
        graphics.text(font, l7, tx, ty + lineH * 6, 0xFF94A3B8, true);
    }

    private static void appendRoundedInt(StringBuilder sb, double value) {
        if (!Double.isFinite(value)) {
            sb.append('0');
            return;
        }
        sb.append(Math.round(value));
    }

    private static void appendFixed1(StringBuilder sb, double value) {
        if (!Double.isFinite(value)) {
            sb.append("0.0");
            return;
        }
        long scaled = Math.round(value * 10.0D);
        if (scaled < 0L) {
            sb.append('-');
            if (scaled == Long.MIN_VALUE) {
                scaled = Long.MAX_VALUE;
            } else {
                scaled = -scaled;
            }
        }
        sb.append(scaled / 10L).append('.').append(scaled % 10L);
    }

    private static void appendFixed2(StringBuilder sb, double value) {
        if (!Double.isFinite(value)) {
            sb.append("0.00");
            return;
        }
        long scaled = Math.round(value * 100.0D);
        if (scaled < 0L) {
            sb.append('-');
            if (scaled == Long.MIN_VALUE) {
                scaled = Long.MAX_VALUE;
            } else {
                scaled = -scaled;
            }
        }
        long frac = scaled % 100L;
        sb.append(scaled / 100L).append('.');
        if (frac < 10L) {
            sb.append('0');
        }
        sb.append(frac);
    }
}
