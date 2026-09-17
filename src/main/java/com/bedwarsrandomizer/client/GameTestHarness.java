package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.arena.Arena;
import com.bedwarsrandomizer.block.teambed.TeamBedBlock;
import com.bedwarsrandomizer.drop.RandomizerSource;
import com.bedwarsrandomizer.game.BedwarsGame;
import com.bedwarsrandomizer.game.GameSettings;
import com.bedwarsrandomizer.game.RefillArea;
import com.bedwarsrandomizer.replay.ReplayRecorder;
import com.bedwarsrandomizer.replay.ReplayStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.GlazedTerracottaBlock;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Dev-only check of whole rounds, enabled with {@code gradlew runClient -PgameTest} (needs Curtain in run/mods for the
 * fake players Bob and Carl). The real player and Bob play, Carl hosts: register, start, spawns, host spectating,
 * refill, own bed protection, breaking a bed, dying and respawning, a final kill, the win with stats, back to the
 * lobby, [Next round] (old replays gone), [End round] (stats reset) and the settings screen.
 */
@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID, value = Dist.CLIENT)
public final class GameTestHarness {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("bwr.gameTest", "false"));

    private static int ticks;
    private static BlockPos refillPos;
    private static UUID bobId;
    private static UUID carlId;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        confirmExperimentalWarning(mc);
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;

        switch (++ticks) {
            case 40 -> onServer(mc, player -> {
                run(player, "player Bob spawn at 3.5 120 63.5");
                run(player, "player Carl spawn at -3.5 120 63.5");
            });
            case 100 -> onServer(mc, GameTestHarness::startGame);
            case 175 -> onServer(mc, GameTestHarness::checkSpawns);
            case 180 -> shot(mc, "game_01_spawn.png");
            case 181 -> onServer(mc, GameTestHarness::lookAtRefillText);
            case 184 -> shot(mc, "game_01b_refill_text.png");
            case 185 -> onServer(mc, GameTestHarness::breakBlocks);
            case 285 -> onServer(mc, GameTestHarness::checkRefill);
            case 290 -> onServer(mc, player -> player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE));
            case 315 -> onServer(mc, GameTestHarness::checkWaiting);
            case 385 -> onServer(mc, GameTestHarness::checkRespawned);
            case 390 -> onServer(mc, GameTestHarness::killBob);
            case 410 -> onServer(mc, GameTestHarness::checkWinAndStats);
            case 415 -> shot(mc, "game_02_win.png");
            case 440 -> onServer(mc, player -> BedwarsRandomizer.LOGGER.info("[gameTest] replays stored this round: {}", ReplayStorage.all().size()));
            case 630 -> onServer(mc, GameTestHarness::checkLobby);
            case 632 -> onServer(mc, GameTestHarness::checkOfflineBlocksStart);
            case 634 -> onServer(mc, player -> run(player, "player Bob spawn at 3.5 120 63.5"));
            case 660 -> onServer(mc, player -> run(player, "bwr nextround"));
            case 665 -> onServer(mc, GameTestHarness::checkNoPvpBeforeStart);
            case 670 -> onServer(mc, GameTestHarness::checkNextRound);
            case 680 -> onServer(mc, player -> run(player, "bwr endround"));
            case 690 -> onServer(mc, GameTestHarness::checkEndRound);
            case 695 -> shot(mc, "game_03_endround.png");
            case 700 -> onServer(mc, player -> run(player, "bwr lobbyall"));
            case 740 -> shot(mc, "game_04_maps.png");
            case 745 -> onServer(mc, GameTestHarness::checkTeleportAll);
            case 750 -> onServer(mc, player -> run(player, "bwr setting"));
            case 790 -> shot(mc, "game_05_settings.png");
            case 800 -> mc.stop();
            default -> {}
        }
    }

    private static void startGame(ServerPlayer player) {
        GameSettings settings = GameSettings.get();
        settings.unregisterAll();
        settings.setNumbers(3, 3, 9, 1);
        settings.setRefillSeconds(RandomizerSource.GLAZED_TERRACOTTA, 4);
        settings.save();
        // alone, there is only one team: no start
        settings.register(player);
        Component oneTeam = BedwarsGame.get().start(player.getServer(), player);
        BedwarsRandomizer.LOGGER.info("[gameTest] one-team start blocked {} ({})",
                oneTeam != null && BedwarsGame.get().phase() == BedwarsGame.Phase.LOBBY ? "PASS" : "FAIL",
                oneTeam == null ? "it started" : oneTeam.getString());
        run(player, "bwr regisall");
        run(player, "bwr host add Carl");
        run(player, "bwr start");
        BedwarsRandomizer.LOGGER.info("[gameTest] registered {}, phase {}", settings.players().stream()
                .map(registered -> registered.name() + (registered.host() ? " (host)" : "")).toList(), BedwarsGame.get().phase());
    }

    private static void checkSpawns(ServerPlayer self) {
        MinecraftServer server = self.getServer();
        BedwarsGame game = BedwarsGame.get();
        ServerLevel arena = Arena.level(server);
        int total = 0, atBase = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            DyeColor team = game.teamOf(player.getUUID());
            if (team == null) continue;
            total++;
            BlockPos bed = game.bedOf(team);
            double distance = Math.hypot(player.getX() - (bed.getX() + 0.5), player.getZ() - (bed.getZ() + 0.5));
            boolean good = player.level() == arena && Math.abs(distance - 9) < 1.0
                    && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL && player.getInventory().isEmpty();
            if (good) atBase++;
            BedwarsRandomizer.LOGGER.info("[gameTest] {} team={} bed={} at={} distance to bed={} ok={}", player.getGameProfile().getName(),
                    team.getName(), bed.toShortString(), player.blockPosition().toShortString(), String.format("%.1f", distance), good);
        }
        ServerPlayer carl = server.getPlayerList().getPlayerByName("Carl");
        carlId = carl.getUUID();
        boolean hostWatching = game.teamOf(carlId) == null && game.isHostInRound(carlId) && carl.isSpectator()
                && carl.position().distanceTo(BedwarsGame.WAITING_SPOT) < 2;
        int displays = arena.getEntities(EntityTypeTest.forClass(Display.TextDisplay.class), e -> e.getTags().contains(RefillArea.DISPLAY_TAG)).size();
        int beds = Arena.findTeamBeds(server).size();
        boolean pass = game.phase() == BedwarsGame.Phase.RUNNING && total == 2 && atBase == 2 && hostWatching
                && displays >= 2 && displays <= game.refillAreas().size() && beds == 2;
        BedwarsRandomizer.LOGGER.info("[gameTest] start {} (players={} at their base={} host Carl spectating={} refill texts={} areas={} beds left={})",
                pass ? "PASS" : "FAIL", total, atBase, hostWatching, displays, game.refillAreas().size(), beds);
        BedwarsRandomizer.LOGGER.info("[gameTest] host Carl: in round={} team={} gamemode={} dimension={} at={}", game.isHostInRound(carlId),
                game.teamOf(carlId), carl.gameMode.getGameModeForPlayer(), carl.level().dimension().location(), carl.position());
    }

    /** Stands a few blocks in front of the team's glazed terracotta area, looking at it, to see the refill countdown. */
    private static void lookAtRefillText(ServerPlayer player) {
        BedwarsGame game = BedwarsGame.get();
        Vec3 spawn = game.spawnOf(game.teamOf(player.getUUID()));
        RefillArea area = game.refillAreas().stream().filter(a -> a.sources().contains(RandomizerSource.GLAZED_TERRACOTTA))
                .min(Comparator.comparingDouble(a -> Vec3.atCenterOf(a.box().getCenter()).distanceToSqr(spawn))).orElseThrow();
        Vec3 center = Vec3.atCenterOf(area.box().getCenter());
        Vec3 away = new Vec3(spawn.x - center.x, 0, spawn.z - center.z).normalize();
        Vec3 eye = center.add(away.scale(6)).add(0, 1, 0);
        Vec3 look = center.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        player.teleportTo(player.serverLevel(), eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
    }

    private static void breakBlocks(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        BedwarsGame game = BedwarsGame.get();
        ServerLevel arena = player.serverLevel();
        DyeColor team = game.teamOf(player.getUUID());
        BlockPos ownBed = game.bedOf(team);
        boolean ownBedKept = !player.gameMode.destroyBlock(ownBed) && arena.getBlockState(ownBed).getBlock() instanceof TeamBedBlock && game.bedAlive(team);

        Vec3 spawn = game.spawnOf(team);
        RefillArea area = game.refillAreas().stream().filter(a -> a.sources().contains(RandomizerSource.GLAZED_TERRACOTTA))
                .min(Comparator.comparingDouble(a -> Vec3.atCenterOf(a.box().getCenter()).distanceToSqr(spawn))).orElseThrow();
        refillPos = area.positions(RandomizerSource.GLAZED_TERRACOTTA).iterator().next();
        boolean glazedBroken = player.gameMode.destroyBlock(refillPos) && arena.getBlockState(refillPos).isAir();

        bobId = server.getPlayerList().getPlayerByName("Bob").getUUID();
        DyeColor bobTeam = game.teamOf(bobId);
        boolean otherBedBroken = player.gameMode.destroyBlock(game.bedOf(bobTeam)) && !game.bedAlive(bobTeam);

        // workstations stay closed during the round, storage opens
        BlockPos table = new BlockPos(0, 140, 0);
        BlockPos chest = new BlockPos(2, 140, 0);
        arena.setBlock(table, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        arena.setBlock(chest, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        use(player, table);
        boolean tableClosed = player.containerMenu == player.inventoryMenu;
        use(player, chest);
        boolean chestOpened = player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu;
        player.closeContainer();
        BedwarsRandomizer.LOGGER.info("[gameTest] workstations {} (crafting table stayed closed={} chest opened={})",
                tableClosed && chestOpened ? "PASS" : "FAIL", tableClosed, chestOpened);

        boolean pass = ownBedKept && glazedBroken && otherBedBroken;
        BedwarsRandomizer.LOGGER.info("[gameTest] beds {} (my team={} own bed kept={} Bob's {} bed broken={} glazed broken={})",
                pass ? "PASS" : "FAIL", team.getName(), ownBedKept, bobTeam.getName(), otherBedBroken, glazedBroken);
    }

    private static void use(ServerPlayer player, BlockPos pos) {
        player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false));
    }

    private static void checkRefill(ServerPlayer player) {
        ServerLevel arena = player.serverLevel();
        boolean refilled = arena.getBlockState(refillPos).getBlock() instanceof GlazedTerracottaBlock;
        int displays = arena.getEntities(EntityTypeTest.forClass(Display.TextDisplay.class), e -> e.getTags().contains(RefillArea.DISPLAY_TAG)).size();
        boolean noCopies = displays <= BedwarsGame.get().refillAreas().size();
        BedwarsRandomizer.LOGGER.info("[gameTest] refill {} (broken glazed terracotta back={}, refill texts={} no copies={})",
                refilled && noCopies ? "PASS" : "FAIL", refilled, displays, noCopies);
    }

    private static void checkWaiting(ServerPlayer player) {
        BedwarsGame game = BedwarsGame.get();
        boolean pass = game.statusOf(player.getUUID()) == BedwarsGame.Status.RESPAWNING && player.isSpectator()
                && player.position().distanceTo(BedwarsGame.WAITING_SPOT) < 2 && Arena.isArena(player.level());
        BedwarsRandomizer.LOGGER.info("[gameTest] death with bed {} (status={} spectator={} at={})", pass ? "PASS" : "FAIL",
                game.statusOf(player.getUUID()), player.isSpectator(), player.blockPosition().toShortString());
    }

    private static void checkRespawned(ServerPlayer player) {
        BedwarsGame game = BedwarsGame.get();
        Vec3 spawn = game.spawnOf(game.teamOf(player.getUUID()));
        boolean pass = game.statusOf(player.getUUID()) == BedwarsGame.Status.ALIVE && !player.isSpectator()
                && player.position().distanceTo(spawn) < 1.5;
        BedwarsRandomizer.LOGGER.info("[gameTest] respawn {} (status={} survival={} distance to spawn={})", pass ? "PASS" : "FAIL",
                game.statusOf(player.getUUID()), !player.isSpectator(), String.format("%.1f", player.position().distanceTo(spawn)));
    }

    /** Bob's bed is gone, so this is a final kill and the real player's team wins. */
    private static void killBob(ServerPlayer player) {
        ServerPlayer bob = player.getServer().getPlayerList().getPlayer(bobId);
        if (bob != null) bob.hurt(player.damageSources().playerAttack(player), Float.MAX_VALUE);
    }

    private static void checkWinAndStats(ServerPlayer player) {
        BedwarsGame game = BedwarsGame.get();
        DyeColor team = game.teamOf(player.getUUID());
        BedwarsGame.Stats mine = game.roundStatsOf(player.getUUID());
        BedwarsGame.Stats bob = game.roundStatsOf(bobId);
        boolean statsOk = mine != null && mine.kills == 1 && mine.finalKills == 1 && mine.bedsBroken == 1 && mine.deaths == 1 && mine.wins == 1
                && bob != null && bob.deaths == 1 && game.roundsPlayed() == 1;
        boolean pass = game.phase() == BedwarsGame.Phase.ENDING && team != null && game.lastWinner() == team && statsOk;
        BedwarsRandomizer.LOGGER.info("[gameTest] win {} (phase={} winner={} my team={} my stats: kills={} final={} beds={} deaths={} wins={}; Bob deaths={} rounds={})",
                pass ? "PASS" : "FAIL", game.phase(), game.lastWinner(), team, mine == null ? -1 : mine.kills, mine == null ? -1 : mine.finalKills,
                mine == null ? -1 : mine.bedsBroken, mine == null ? -1 : mine.deaths, mine == null ? -1 : mine.wins, bob == null ? -1 : bob.deaths,
                game.roundsPlayed());
    }

    private static void checkLobby(ServerPlayer player) {
        BedwarsGame game = BedwarsGame.get();
        boolean pass = game.phase() == BedwarsGame.Phase.LOBBY && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                && Arena.isArena(player.level()) && player.position().distanceTo(new Vec3(0.5, 100, 0.5)) < 2;
        BedwarsRandomizer.LOGGER.info("[gameTest] back to lobby {} (phase={} gamemode={} at={})", pass ? "PASS" : "FAIL", game.phase(),
                player.gameMode.getGameModeForPlayer(), player.blockPosition().toShortString());
    }

    /** Bob is still registered but left when he died: no new round until he's back. */
    private static void checkOfflineBlocksStart(ServerPlayer player) {
        Component error = BedwarsGame.get().nextRound(player.getServer(), player);
        boolean pass = error != null && error.getString().contains("offline") && BedwarsGame.get().phase() == BedwarsGame.Phase.LOBBY;
        BedwarsRandomizer.LOGGER.info("[gameTest] offline player blocks start {} ({})", pass ? "PASS" : "FAIL", error == null ? "it started" : error.getString());
    }

    private static void checkNoPvpBeforeStart(ServerPlayer player) {
        ServerPlayer bob = player.getServer().getPlayerList().getPlayer(bobId);
        float before = bob == null ? 0 : bob.getHealth();
        boolean blocked = bob != null && !bob.hurt(player.damageSources().playerAttack(player), 4) && bob.getHealth() == before;
        boolean pass = BedwarsGame.get().phase() == BedwarsGame.Phase.COUNTDOWN && blocked;
        BedwarsRandomizer.LOGGER.info("[gameTest] no pvp before the round {} (phase={} hit blocked={})", pass ? "PASS" : "FAIL",
                BedwarsGame.get().phase(), blocked);
    }

    private static void checkTeleportAll(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        // every map in the mod pastes with all 8 team beds; switching clears the previous one
        List<String> maps = Arena.availableMaps(server);
        StringBuilder bedsPerMap = new StringBuilder();
        boolean mapsOk = maps.size() >= 3;
        for (String map : maps) {
            boolean selected = Arena.selectMap(server, map);
            int beds = Arena.findTeamBeds(server).size();
            bedsPerMap.append(' ').append(map).append('=').append(beds);
            if (!selected || beds != 8) mapsOk = false;
        }
        boolean known = Arena.selectMap(server, Arena.BUNDLED_MAP_NAME);
        int count = BedwarsGame.teleportAllToLobby(server);
        boolean pass = mapsOk && known && count >= 2 && player.position().distanceTo(new Vec3(0.5, 100, 0.5)) < 2;
        BedwarsRandomizer.LOGGER.info("[gameTest] maps + teleport all to lobby {} (beds per map:{} teleported={})", pass ? "PASS" : "FAIL",
                bedsPerMap, count);
    }

    private static void checkNextRound(ServerPlayer player) {
        BedwarsGame game = BedwarsGame.get();
        boolean pass = game.phase() == BedwarsGame.Phase.COUNTDOWN && ReplayStorage.all().isEmpty() && ReplayRecorder.get().isRecording()
                && game.sessionStatsOf(player.getUUID()) != null;
        BedwarsRandomizer.LOGGER.info("[gameTest] next round {} (phase={} old replays left={} recording={} stats kept={})", pass ? "PASS" : "FAIL",
                game.phase(), ReplayStorage.all().size(), ReplayRecorder.get().isRecording(), game.sessionStatsOf(player.getUUID()) != null);
    }

    private static void checkEndRound(ServerPlayer player) {
        BedwarsGame game = BedwarsGame.get();
        boolean pass = game.phase() == BedwarsGame.Phase.LOBBY && game.sessionStatsOf(player.getUUID()) == null && game.roundsPlayed() == 0;
        BedwarsRandomizer.LOGGER.info("[gameTest] end round {} (phase={} stats cleared={})", pass ? "PASS" : "FAIL", game.phase(),
                game.sessionStatsOf(player.getUUID()) == null);
    }

    private static void confirmExperimentalWarning(Minecraft mc) {
        if (mc.screen instanceof ConfirmScreen confirm
                && confirm.getTitle().getContents() instanceof TranslatableContents title
                && title.getKey().equals("selectWorld.backupQuestion.experimental")) {
            for (var child : confirm.children()) {
                if (child instanceof Button button && button.getMessage().equals(CommonComponents.GUI_PROCEED)) {
                    button.onPress();
                    return;
                }
            }
        }
    }

    private static void run(ServerPlayer player, String command) {
        player.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command);
    }

    private static void onServer(Minecraft mc, Consumer<ServerPlayer> action) {
        IntegratedServer server = mc.getSingleplayerServer();
        UUID playerId = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) action.accept(player);
        });
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {});
        BedwarsRandomizer.LOGGER.info("[gameTest] screenshot {}", name);
    }

    private GameTestHarness() {}
}
