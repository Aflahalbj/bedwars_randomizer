package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.replay.ActorFrame;
import com.bedwarsrandomizer.replay.KillCause;
import com.bedwarsrandomizer.replay.ReplayData;
import com.bedwarsrandomizer.replay.ReplayStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

/**
 * Dev-only visual test for kill replays, enabled with {@code gradlew runClient -PreplayTest[=curtain]}.
 * Builds a small arena in the singleplayer test world, produces a kill (scripted, or two Curtain fake players
 * really fighting while a third one places blocks and arrows/a trident fly by), plays the replay, saves a screenshot
 * every second to {@code run/screenshots} (logging the camera), then closes the game.
 */
@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID, value = Dist.CLIENT)
public final class ReplayTestHarness {
    private static final String MODE = System.getProperty("bwr.replayTest", "");
    private static final boolean ENABLED = !MODE.isEmpty() && !MODE.equals("false");
    /** Two Curtain fake players fight for real instead of playing the scripted kill. */
    private static final boolean REAL_FIGHT = MODE.equals("curtain");
    private static final int DEATH_FRAME = 100;
    private static final int[] HITS = {58, 76, 94, DEATH_FRAME};
    private static final float[] HEALTH_AFTER_HIT = {14, 8, 3, 0};

    private static int ticksInWorld;
    private static boolean replayRequested;
    private static int fallbackSeenAt = -1;
    private static BlockPos base;
    private static boolean played;
    private static int ticksAfterReplay;
    private static double nextShot = 0.9;
    private static int shotIndex;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;

        ticksInWorld++;
        if (ticksInWorld == 40) {
            prepareArena(mc);
        } else if (ticksInWorld == 70) {
            buildArena(mc);
        } else if (REAL_FIGHT) {
            realFight(mc);
        } else if (ticksInWorld == 120) {
            ClientReplayHandler.play(scenario(mc));
        }

        ReplayPlayback playback = ClientReplayHandler.current();
        if (playback != null) {
            if (!played) logTracks(playback.data);
            played = true;
        } else if (played && ++ticksAfterReplay == 40) {
            BedwarsRandomizer.LOGGER.info("[replayTest] done, {} screenshots", shotIndex);
            mc.stop();
        }
    }

    /**
     * Killer and Alex fight for real; Builder places wool against the gold pillar; arrows and a trident are shot into
     * the stone wall. The replay the server stores of it is then played back.
     */
    private static void realFight(Minecraft mc) {
        if (base == null) return; // arena not built yet
        int x = base.getX(), y = base.getY(), z = base.getZ();
        if (ticksInWorld == 100) {
            runCommands(mc, List.of(
                    "time set 6000",
                    "weather clear",
                    // fake players don't collide, so the killer stays put in reach and the victim has its back to
                    // the stone wall (x+7) so knockback can't carry it out of reach
                    String.format(Locale.ROOT, "player Killer spawn at %d.5 %d %d.5 facing -90 0", x + 3, y, z),
                    String.format(Locale.ROOT, "player Alex spawn at %d.3 %d %d.5 facing 90 0", x + 6, y, z),
                    String.format(Locale.ROOT, "player Builder spawn at %d.5 %d %d.5 facing 180 0", x - 3, y, z + 7)));
        } else if (ticksInWorld == 160) {
            // fake players join a few ticks after spawning, so their actions come later; Curtain restores a returning
            // fake player's saved position, so put them in place explicitly
            runCommands(mc, List.of(
                    String.format(Locale.ROOT, "tp Killer %d.5 %d %d.5 -90 0", x + 3, y, z),
                    String.format(Locale.ROOT, "tp Alex %d.3 %d %d.5 90 0", x + 6, y, z),
                    String.format(Locale.ROOT, "tp Builder %d.5 %d %d.5 180 0", x - 3, y, z + 7),
                    "gamemode survival Killer",
                    "gamemode survival Alex",
                    "gamemode survival Builder",
                    "item replace entity Killer weapon.mainhand with minecraft:stone_axe",
                    "item replace entity Builder weapon.mainhand with minecraft:white_wool 64",
                    // Builder pillars up on wool: look straight down, jump and place a block underneath every jump
                    "player Builder look down",
                    "bwr replay record",
                    "player Alex jump interval 14",
                    "player Killer jump interval 11",
                    "player Killer attack interval 20",
                    "player Builder jump continuous",
                    "player Builder use continuous"));
        }

        if (ticksInWorld > 165 && ticksInWorld < 400 && ticksInWorld % 30 == 0 && !replayRequested) {
            // shot from the far side into the end of the stone wall, well clear of the fighters at z+0.5
            String entity = ticksInWorld % 90 == 0 ? "minecraft:trident" : "minecraft:arrow";
            runCommands(mc, List.of(String.format(Locale.ROOT,
                    "summon %s %d.5 %d.5 %d.5 {Motion:[1.38d,-0.05d,0.54d]}", entity, x - 2, y + 3, z - 6)));
        }

        ReplayData latest = ReplayStorage.latest("Killer");
        if (ticksInWorld > 160 && !replayRequested && latest != null) {
            if (fallbackSeenAt < 0) fallbackSeenAt = ticksInWorld;
            // give the client recording a moment to replace the server's fallback
            if (latest.exact || ticksInWorld - fallbackSeenAt > 100) {
                replayRequested = true;
                BedwarsRandomizer.LOGGER.info("[replayTest] playing {} replay", latest.exact ? "client-recorded" : "server-recorded");
                runCommands(mc, List.of("player Killer stop", "player Builder stop",
                        "bwr replay " + mc.player.getGameProfile().getName() + " kill Killer Alex"));
            }
        } else if (ticksInWorld == 160 + 20 * 60 && !replayRequested) {
            BedwarsRandomizer.LOGGER.warn("[replayTest] no kill happened within a minute");
            mc.stop();
        }
    }

    private static void logTracks(ReplayData data) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ReplayData.Actor actor : data.actors) {
            String kind = actor.isPlayer() ? "player " + actor.name : BuiltInRegistries.ENTITY_TYPE.getKey(actor.type).getPath();
            counts.merge(kind, 1, Integer::sum);
        }
        BedwarsRandomizer.LOGGER.info("[replayTest] replay exact={} frames={} death={} blocks={} tracks={}",
                data.exact, data.frameCount, data.deathFrame, data.blockChanges.size(), counts);
    }

    private static void runCommands(Minecraft mc, List<String> commands) {
        IntegratedServer server = mc.getSingleplayerServer();
        UUID playerId = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) return;
            CommandSourceStack source = player.createCommandSourceStack().withPermission(4);
            for (String command : commands) {
                server.getCommands().performPrefixedCommand(source, command);
            }
        });
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        ReplayPlayback playback = ClientReplayHandler.current();
        if (playback != null) logAnimation(playback);
        if (playback == null || playback.realTime() < nextShot) return;
        nextShot = playback.realTime() + 1.0;

        Minecraft mc = Minecraft.getInstance();
        String name = String.format(Locale.ROOT, "replay_%02d_take%d.png", shotIndex++, playback.takeIndex() + 1);
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {});
        ReplayDirector.CameraPose pose = playback.pose();
        BedwarsRandomizer.LOGGER.info("[replayTest] {} frame={} cam={} yaw={} pitch={} fov={}",
                name, String.format(Locale.ROOT, "%.1f", playback.replayTime()), pose.pos(), pose.yaw(), pose.pitch(), pose.fov());
        LivingEntity killer = playback.puppet(playback.data.killerId);
        if (killer != null) {
            // which way the killer puppet really faces, and where the camera is relative to it
            double toCamera = Mth.atan2(pose.pos().z - killer.getZ(), pose.pos().x - killer.getX()) * Mth.RAD_TO_DEG - 90;
            BedwarsRandomizer.LOGGER.info(String.format(Locale.ROOT,
                    "[replayFacing] %s killer pos=(%.2f, %.2f, %.2f) yRot=%.1f body=%.1f head=%.1f cameraBearing=%.1f",
                    name, killer.getX(), killer.getY(), killer.getZ(),
                    killer.getYRot(), killer.yBodyRot, killer.yHeadRot, Mth.wrapDegrees(toCamera)));
        }
    }

    /** Per rendered frame around the last hits of the slowest take: does the arm swing really crawl too? */
    private static void logAnimation(ReplayPlayback playback) {
        if (playback.takeIndex() != 2 || playback.replayTime() < 90 || playback.replayTime() > 108) return;
        LivingEntity killer = playback.puppet(playback.data.killerId);
        LivingEntity victim = playback.puppet(playback.data.victimId);
        if (killer == null || victim == null) return;
        float partial = playback.partial();
        BedwarsRandomizer.LOGGER.info(String.format(Locale.ROOT,
                "[replayAnim] real=%.3f frame=%.3f partial=%.3f attack=%.3f walk=%.3f victimX=%.4f victimHurt=%d",
                playback.realTime(), playback.replayTime(), partial,
                killer.getAttackAnim(partial), killer.walkAnimation.position(partial), victim.getX(), victim.hurtTime));
    }

    /**
     * The test world is reused between runs: Curtain brings its fake players back where they were saved, and the local
     * player starts wherever it left. Use a fixed arena position, remove old fake players, and move there first so the
     * arena chunks are loaded before filling.
     */
    private static void prepareArena(Minecraft mc) {
        base = new BlockPos(0, 120, 0);
        runCommands(mc, List.of(
                "gamerule doDaylightCycle false",
                "gamerule doWeatherCycle false",
                "gamerule doMobSpawning false",
                "gamemode creative",
                "player Killer kill",
                "player Alex kill",
                "player Builder kill",
                "kill @e[type=!minecraft:player]",
                String.format(Locale.ROOT, "tp @s %d %d %d", base.getX(), base.getY(), base.getZ() + 12)));
    }

    private static void buildArena(Minecraft mc) {
        int x = base.getX(), y = base.getY(), z = base.getZ();

        List<String> commands = List.of(
                "time set 18000",
                "weather rain",
                fill(x - 14, y - 1, z - 14, x + 14, y - 1, z + 14, "black_concrete"),
                fill(x - 14, y, z - 14, x + 14, y + 8, z + 14, "air"),
                fill(x - 10, y - 1, z - 2, x - 8, y - 1, z + 2, "lime_glazed_terracotta"),
                fill(x - 3, y, z + 4, x - 3, y + 5, z + 4, "gold_block"),
                fill(x + 7, y, z - 3, x + 7, y + 2, z + 2, "stone_bricks"),
                String.format(Locale.ROOT, "tp @s %d %d %d", x, y, z + 12));
        runCommands(mc, commands);
    }

    private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x1, y1, z1, x2, y2, z2, block);
    }

    /** Killer walks in from the west and lands four hits; the victim is knocked back east on each and dies on the last. */
    private static ReplayData scenario(Minecraft mc) {
        int frameCount = DEATH_FRAME + 21;
        double bx = base.getX() + 0.5, by = base.getY(), bz = base.getZ() + 0.5;
        ActorFrame[] killer = new ActorFrame[frameCount];
        ActorFrame[] victim = new ActorFrame[frameCount];

        for (int i = 0; i < frameCount; i++) {
            int f = Math.min(i, DEATH_FRAME);
            double walkIn = Math.min(1, f / 50.0);
            double kx = bx + Mth.lerp(walkIn, -8.0, -1.0) + Math.max(0, f - 50) * 0.04;
            double kz = bz + Mth.lerp(walkIn, -2.0, 0.0) + (f > 50 ? 0.5 * Math.sin((f - 50) * 0.12) : 0);
            double approach = Math.min(1, f / 45.0);
            double vx = bx + Mth.lerp(approach, 5.0, 1.2) + knockback(f);
            double vz = bz + Mth.lerp(approach, 3.0, 0.4) + (f > 45 ? -0.4 * Math.sin((f - 45) * 0.15) : 0);

            float killerYaw = (float) (Mth.atan2(vz - kz, vx - kx) * Mth.RAD_TO_DEG) - 90.0F;
            float victimYaw = killerYaw + 180.0F;

            int killerFlags = ActorFrame.FLAG_ON_GROUND | (f > 50 ? ActorFrame.FLAG_SPRINTING : 0);
            int hurtTime = 0;
            float health = 20;
            for (int h = 0; h < HITS.length; h++) {
                int hit = HITS[h];
                if (i == hit - 2) killerFlags |= ActorFrame.FLAG_SWING;
                if (i >= hit && i < hit + 10) hurtTime = 10 - (i - hit);
                if (i >= hit) health = HEALTH_AFTER_HIT[h];
            }
            int deathTime = i > DEATH_FRAME ? Math.min(20, i - DEATH_FRAME) : 0;

            killer[i] = ActorFrame.basic(kx, by, kz, killerYaw, 10, (byte) 0, killerFlags,
                    (byte) 0, (byte) 0, 20, 20, 0);
            victim[i] = ActorFrame.basic(vx, by, vz, victimYaw, 0, (byte) 0, ActorFrame.FLAG_ON_GROUND,
                    (byte) (i > DEATH_FRAME ? 0 : hurtTime), (byte) deathTime, health, 20, 0);
        }

        ItemStack[] killerGear = {new ItemStack(Items.DIAMOND_SWORD), ItemStack.EMPTY, ItemStack.EMPTY,
                new ItemStack(Items.IRON_CHESTPLATE), ItemStack.EMPTY, ItemStack.EMPTY};
        ItemStack[] victimGear = {new ItemStack(Items.WOODEN_SWORD), ItemStack.EMPTY, new ItemStack(Items.LEATHER_HELMET),
                new ItemStack(Items.LEATHER_CHESTPLATE), ItemStack.EMPTY, ItemStack.EMPTY};
        UUID killerId = UUID.randomUUID();
        UUID victimId = UUID.randomUUID();

        return new ReplayData(UUID.randomUUID(), System.currentTimeMillis(), killerId, "Killer", victimId, "Alex",
                mc.level.dimension(), KillCause.MELEE, new ItemStack(Items.DIAMOND_SWORD), frameCount, DEATH_FRAME,
                List.of(new ReplayData.Actor(killerId, "Killer", List.<ItemStack[]>of(killerGear), killer),
                        new ReplayData.Actor(victimId, "Alex", List.<ItemStack[]>of(victimGear), victim)),
                List.of(), false);
    }

    /** Each hit pushes the victim 0.9 blocks east over three ticks. */
    private static double knockback(int frame) {
        double total = 0;
        for (int hit : HITS) {
            if (frame >= hit) total += 0.9 * Math.min(1, (frame - hit + 1) / 3.0);
        }
        return total;
    }

    private ReplayTestHarness() {}
}
