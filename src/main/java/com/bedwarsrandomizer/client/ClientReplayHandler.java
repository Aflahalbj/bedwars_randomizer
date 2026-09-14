package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.replay.ReplayData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client entry point for replays; hooks the playback into rendering, camera, HUD and input. */
@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID, value = Dist.CLIENT)
public final class ClientReplayHandler {
    private static final double RETURN_FADE_SECONDS = 0.7;

    private static ReplayPlayback playback;
    private static long returnFadeStart = -1;

    public static void play(ReplayData data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (!mc.level.dimension().equals(data.dimension)) {
            mc.player.displayClientMessage(Component.literal("[BWR] That replay happened in another dimension.")
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        if (data.actor(data.victimId) == null) return;

        if (playback != null) {
            playback.stop();
        }
        playback = new ReplayPlayback(mc, mc.level, data);
        playback.start();
    }

    static ReplayPlayback current() {
        return playback;
    }

    public static void stop() {
        if (playback != null) {
            playback.stop();
            playback = null;
            returnFadeStart = System.nanoTime();
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || playback == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != playback.level() || mc.player == null) {
            playback.abort();
            playback = null;
            return;
        }
        playback.update();
        if (playback.isFinished()) {
            stop();
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (playback != null && event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            playback.renderActors(event.getPoseStack(), event.getCamera());
        }
    }

    /** Hide the live players; only the recorded puppets should be on screen. */
    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (playback != null && !(event.getEntity() instanceof ReplayActor)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (playback != null) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (playback != null) event.setRoll(playback.roll());
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (playback != null && event.usedConfiguredFov()) event.setFOV(playback.fov());
    }

    @SubscribeEvent
    public static void onHudElement(RenderGuiOverlayEvent.Pre event) {
        if (playback != null) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        if (playback != null) {
            ReplayOverlay.render(event.getGuiGraphics(), playback);
        } else if (returnFadeStart >= 0) {
            // covers the camera sliding back to the player's eye height
            double elapsed = (System.nanoTime() - returnFadeStart) / 1.0E9;
            if (elapsed >= RETURN_FADE_SECONDS) {
                returnFadeStart = -1;
            } else {
                float alpha = elapsed < 0.3 ? 1 : (float) (1 - (elapsed - 0.3) / (RETURN_FADE_SECONDS - 0.3));
                ReplayOverlay.fillBlack(event.getGuiGraphics(), alpha);
            }
        }
    }

    @SubscribeEvent
    public static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (playback != null) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (playback != null) {
            playback.abort();
            playback = null;
        }
        returnFadeStart = -1;
    }

    private ClientReplayHandler() {}
}
