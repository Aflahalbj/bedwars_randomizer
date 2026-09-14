package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.replay.ReplayData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Minimal cinematic HUD: letterbox bars with one "killer ⚔ victim" line, the kill flash and fades. */
final class ReplayOverlay {

    static void render(GuiGraphics g, ReplayPlayback p) {
        Font font = Minecraft.getInstance().font;
        int w = g.guiWidth();
        int h = g.guiHeight();
        ReplayData data = p.data;

        float barIn = easeOut((float) Math.min(1, p.realTime() / 0.8));
        int bar = Math.round(h * 0.1F * barIn);
        g.fill(0, 0, w, bar, 0xFF000000);
        g.fill(0, h - bar, w, h, 0xFF000000);

        if (barIn > 0.8F) {
            Component title = Component.literal(data.killerName).withStyle(ChatFormatting.RED)
                    .append(Component.literal("  ⚔  ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(data.victimName).withStyle(ChatFormatting.GRAY));
            g.drawCenteredString(font, title, w / 2, h - bar / 2 - 4, 0xFFFFFFFF);
        }

        if (p.flash() > 0) {
            g.fill(0, 0, w, h, withAlpha(0xFFFFFF, (int) (p.flash() * 160)));
        }
        fillBlack(g, p.blackness());
    }

    static void fillBlack(GuiGraphics g, float alpha) {
        if (alpha > 0) {
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), withAlpha(0x000000, (int) (alpha * 255)));
        }
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static float easeOut(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private ReplayOverlay() {}
}
