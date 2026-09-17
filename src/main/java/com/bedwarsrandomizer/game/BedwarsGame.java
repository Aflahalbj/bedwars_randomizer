package com.bedwarsrandomizer.game;

import com.bedwarsrandomizer.arena.Arena;
import com.bedwarsrandomizer.block.selection.SelectionBlockEntity;
import com.bedwarsrandomizer.block.selection.SelectionTracker;
import com.bedwarsrandomizer.block.teambed.TeamBedBlock;
import com.bedwarsrandomizer.drop.RandomizerSource;
import com.bedwarsrandomizer.replay.ReplayRecorder;
import com.bedwarsrandomizer.replay.ReplayStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * One Bed Wars round in the arena: shuffled teams on randomly chosen (but spread out) islands, start countdown,
 * spawning in front of the team bed, respawns while the bed stands, eliminations, refills, the winner, and kill /
 * death stats per round and over all rounds until [End round].
 */
public final class BedwarsGame {
    public enum Phase { LOBBY, COUNTDOWN, RUNNING, ENDING }

    public enum Status { ALIVE, RESPAWNING, ELIMINATED, DISCONNECTED }

    /** One player's numbers, for a round or added up over the rounds. */
    public static final class Stats {
        public final String name;
        public int kills;
        public int finalKills;
        public int deaths;
        public int bedsBroken;
        public int wins;

        Stats(String name) {
            this.name = name;
        }
    }

    /** The islands clockwise around the map. */
    private static final List<DyeColor> RING = List.of(DyeColor.LIME, DyeColor.YELLOW, DyeColor.CYAN, DyeColor.WHITE,
            DyeColor.PINK, DyeColor.GRAY, DyeColor.RED, DyeColor.BLUE);
    /** Ring positions in the order teams get them: the opposite island first, then the gaps, so teams are spread. */
    private static final int[] SPREAD = {0, 4, 2, 6, 1, 3, 7, 5};
    /** The unshuffled island order as numbered on the map overview: 1 lime, 2 pink, 3 cyan, 4 red, ... */
    public static final List<DyeColor> ISLAND_ORDER = islandOrder(0, false);
    /** Where dead players wait (as spectators), eliminated players stay and hosts watch. */
    public static final Vec3 WAITING_SPOT = new Vec3(0.5, 87, 0.5);
    private static final int END_TICKS = 200;
    private static final int VOID_Y = -25;
    private static final BedwarsGame INSTANCE = new BedwarsGame();

    private static final class TeamState {
        final DyeColor color;
        final BlockPos bedHead;
        final Vec3 spawn;
        final float spawnYaw;
        final List<UUID> members = new ArrayList<>();
        boolean bedAlive = true;

        TeamState(DyeColor color, BlockPos bedHead, Vec3 spawn, float spawnYaw) {
            this.color = color;
            this.bedHead = bedHead;
            this.spawn = spawn;
            this.spawnYaw = spawnYaw;
        }
    }

    private static final class PlayerState {
        final UUID id;
        final String name;
        final DyeColor team;
        Status status = Status.ALIVE;
        int respawnTicks;

        PlayerState(UUID id, String name, DyeColor team) {
            this.id = id;
            this.name = name;
            this.team = team;
        }
    }

    private Phase phase = Phase.LOBBY;
    private int countdownTicks;
    private int endTicks;
    private int ticks;
    private final Map<DyeColor, TeamState> teams = new LinkedHashMap<>();
    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private final List<UUID> hosts = new ArrayList<>();
    private final List<RefillArea> refillAreas = new ArrayList<>();
    private final EnumMap<RandomizerSource, Integer> refillTicks = new EnumMap<>(RandomizerSource.class);
    private final Map<UUID, Stats> roundStats = new LinkedHashMap<>();
    private final Map<UUID, Stats> sessionStats = new LinkedHashMap<>();
    private int roundsPlayed;
    @Nullable
    private UUID starter;
    @Nullable
    private DyeColor lastWinner;

    public static BedwarsGame get() {
        return INSTANCE;
    }

    /** Random islands that are still spread out: the ring turned by a random amount, sometimes mirrored. */
    public static List<DyeColor> randomIslandOrder(Random random) {
        return islandOrder(random.nextInt(RING.size()), random.nextBoolean());
    }

    static List<DyeColor> islandOrder(int rotation, boolean mirrored) {
        List<DyeColor> order = new ArrayList<>(SPREAD.length);
        for (int offset : SPREAD) order.add(RING.get(Math.floorMod(rotation + (mirrored ? -offset : offset), RING.size())));
        return List.copyOf(order);
    }

    // ------------------------------------------------------------------------------------------
    // Start / stop / rounds

    /** Starts the countdown. Returns an error message instead when the round can't start. */
    @Nullable
    public Component start(MinecraftServer server, @Nullable ServerPlayer startedBy) {
        if (phase != Phase.LOBBY) return Component.literal("A round is already running.");
        ServerLevel arena = Arena.level(server);
        if (arena == null) return Component.literal("The arena dimension is missing.");
        GameSettings settings = GameSettings.get();
        List<ServerPlayer> online = new ArrayList<>();
        List<ServerPlayer> onlineHosts = new ArrayList<>();
        List<String> offline = new ArrayList<>();
        for (GameSettings.Registered registered : settings.players()) {
            ServerPlayer player = server.getPlayerList().getPlayer(registered.id());
            if (player == null) {
                offline.add(registered.name());
                continue;
            }
            (registered.host() ? onlineHosts : online).add(player);
        }
        if (!offline.isEmpty()) {
            return Component.literal("Can't start: registered player(s) offline: " + String.join(", ", offline)
                    + ". Wait for them or use /bwr unregis <name>.");
        }
        if (online.isEmpty()) {
            return Component.literal("No registered player (hosts don't play). Use /bwr regis <player> or /bwr regisall.");
        }

        Random random = ThreadLocalRandom.current();
        Collections.shuffle(online, random);
        List<DyeColor> islands = randomIslandOrder(random);
        Map<UUID, DyeColor> assignment = assignTeams(settings, online, islands);
        if (new HashSet<>(assignment.values()).size() < 2) {
            return Component.literal("Can't start: a round needs at least 2 teams. Register more players, lower the team size"
                    + " or give players different teams in /bwr setting.");
        }

        Arena.ensurePasted(server);
        Arena.resetMap(server);
        Map<DyeColor, BlockPos> beds = Arena.findTeamBeds(server);
        for (DyeColor color : new LinkedHashSet<>(assignment.values())) {
            if (!beds.containsKey(color)) return Component.literal("The arena has no " + color.getName() + " team bed.");
        }

        clear();
        roundStats.clear();
        beds.forEach((color, head) -> {
            if (!assignment.containsValue(color)) removeBed(arena, head); // islands nobody plays on
        });
        for (DyeColor color : islands) {
            if (!assignment.containsValue(color)) continue;
            BlockPos head = beds.get(color);
            Direction facing = arena.getBlockState(head).getValue(BedBlock.FACING);
            // the bed's head points into the base
            BlockPos spot = standableSpot(arena, head.relative(facing, settings.spawnDistance), head.getY());
            teams.put(color, new TeamState(color, head, Vec3.atBottomCenterOf(spot), facing.getOpposite().toYRot()));
        }
        for (ServerPlayer player : online) {
            DyeColor color = assignment.get(player.getUUID());
            players.put(player.getUUID(), new PlayerState(player.getUUID(), player.getGameProfile().getName(), color));
            teams.get(color).members.add(player.getUUID());
            resetPlayer(player);
        }
        for (ServerPlayer host : onlineHosts) {
            hosts.add(host.getUUID());
            resetPlayer(host);
            toWaitingSpot(host);
        }

        Arena.applyWorldRules(server);
        GameRules rules = server.getGameRules();
        rules.getRule(GameRules.RULE_KEEPINVENTORY).set(false, server);
        rules.getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(true, server);
        server.setPvpAllowed(true);
        // only this round's kills can be replayed
        ReplayStorage.clear();
        ReplayRecorder.get().start();

        starter = startedBy == null ? null : startedBy.getUUID();
        broadcast(server, Component.literal("Bed Wars is starting! ").withStyle(ChatFormatting.YELLOW).append(teamsText()));
        countdownTicks = settings.startCountdown * 20;
        phase = Phase.COUNTDOWN;
        return null;
    }

    /** [Next round]: ends the finished round if it's still celebrating, then starts a new one. */
    @Nullable
    public Component nextRound(MinecraftServer server, @Nullable ServerPlayer startedBy) {
        if (phase == Phase.ENDING) finish(server);
        if (phase != Phase.LOBBY) return Component.literal("The current round isn't over yet.");
        return start(server, startedBy);
    }

    /** [End round]: stops any round, shows everyone's stats over all rounds (hover a name) and starts counting anew. */
    public void endRounds(MinecraftServer server) {
        if (phase != Phase.LOBBY) finish(server);
        if (sessionStats.isEmpty()) {
            broadcast(server, Component.literal("Bed Wars: no stats yet.").withStyle(ChatFormatting.GRAY));
            return;
        }
        MutableComponent text = Component.literal("Bed Wars stats after " + roundsPlayed + " round(s)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(" (hover a name)\n").withStyle(ChatFormatting.GRAY).withStyle(style -> style.withBold(false)));
        List<Stats> sorted = new ArrayList<>(sessionStats.values());
        sorted.sort(Comparator.comparingInt((Stats stats) -> stats.kills).reversed().thenComparing(stats -> stats.name.toLowerCase(Locale.ROOT)));
        for (int i = 0; i < sorted.size(); i++) {
            Stats stats = sorted.get(i);
            if (i > 0) text.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
            text.append(Component.literal(stats.name).withStyle(style -> style.withColor(ChatFormatting.YELLOW).withBold(false)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, statsCard(stats)))));
        }
        broadcast(server, text);
        broadcast(server, topList("Top kills (all rounds)", sessionStats, stats -> stats.kills));
        broadcast(server, topList("Top deaths (all rounds)", sessionStats, stats -> stats.deaths));
        sessionStats.clear();
        roundsPlayed = 0;
    }

    /** Ends a running round right away: everyone back to the lobby. Returns false if nothing was running. */
    public boolean stop(MinecraftServer server) {
        if (phase == Phase.LOBBY) return false;
        finish(server);
        return true;
    }

    /** The server is stopping (like quitting a singleplayer world): end any round and forget registrations, rounds and replays. */
    public void shutdown(MinecraftServer server) {
        if (phase != Phase.LOBBY) finish(server);
        reset();
        GameSettings.get().unregisterAll();
        ReplayRecorder.get().reset();
        ReplayStorage.clear();
    }

    /** [Teleport all to lobby], after the map is chosen: every online registered player and host. Returns how many. */
    public static int teleportAllToLobby(MinecraftServer server) {
        int count = 0;
        for (GameSettings.Registered registered : GameSettings.get().players()) {
            ServerPlayer player = server.getPlayerList().getPlayer(registered.id());
            if (player != null && Arena.teleportToLobby(player)) {
                player.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
                count++;
            }
        }
        return count;
    }

    /** Forgets the round without touching players. */
    public void reset() {
        clear();
        roundStats.clear();
        sessionStats.clear();
        roundsPlayed = 0;
        phase = Phase.LOBBY;
    }

    static Map<UUID, DyeColor> assignTeams(GameSettings settings, List<ServerPlayer> online, List<DyeColor> islands) {
        Map<UUID, DyeColor> result = new LinkedHashMap<>();
        Map<DyeColor, Integer> counts = new EnumMap<>(DyeColor.class);
        List<ServerPlayer> auto = new ArrayList<>();
        for (ServerPlayer player : online) {
            GameSettings.Registered registered = settings.player(player.getUUID());
            DyeColor chosen = registered == null ? null : registered.team();
            if (chosen != null && islands.contains(chosen)) {
                result.put(player.getUUID(), chosen);
                counts.merge(chosen, 1, Integer::sum);
            } else {
                auto.add(player);
            }
        }
        int teamCount = Math.min(islands.size(), Math.max(counts.size(), (int) Math.ceil(online.size() / (double) settings.playersPerTeam)));
        List<DyeColor> colors = new ArrayList<>(counts.keySet());
        for (DyeColor color : islands) {
            if (colors.size() >= teamCount) break;
            if (!colors.contains(color)) colors.add(color);
        }
        for (ServerPlayer player : auto) {
            DyeColor best = colors.stream()
                    .min(Comparator.comparingInt((DyeColor color) -> counts.getOrDefault(color, 0)).thenComparingInt(islands::indexOf))
                    .orElse(islands.get(0));
            result.put(player.getUUID(), best);
            counts.merge(best, 1, Integer::sum);
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------
    // Ticking

    public void tick(MinecraftServer server) {
        if (phase == Phase.LOBBY) return;
        ServerLevel arena = Arena.level(server);
        if (arena == null) {
            finish(server);
            return;
        }
        ticks++;
        switch (phase) {
            case COUNTDOWN -> {
                if (countdownTicks > 0 && countdownTicks % 20 == 0) {
                    int seconds = countdownTicks / 20;
                    forEachOnline(server, player -> {
                        title(player, Component.literal(String.valueOf(seconds)).withStyle(seconds <= 3 ? ChatFormatting.RED : ChatFormatting.YELLOW),
                                Component.literal("Get ready!"), 0, 25, 0);
                        player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, seconds <= 3 ? 1.4F : 1.0F);
                    });
                }
                if (--countdownTicks < 0) beginRunning(server, arena);
            }
            case RUNNING -> tickRunning(server, arena);
            case ENDING -> {
                if (endTicks > END_TICKS - 100 && endTicks % 20 == 0) launchFireworks(server, arena);
                if (--endTicks <= 0) finish(server);
            }
            default -> {}
        }
    }

    private void beginRunning(MinecraftServer server, ServerLevel arena) {
        phase = Phase.RUNNING;
        for (PlayerState state : players.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(state.id);
            if (player == null) {
                state.status = Status.DISCONNECTED;
                continue;
            }
            spawnAtBase(player, arena, teams.get(state.team));
            title(player, Component.literal("GO!").withStyle(ChatFormatting.GREEN), Component.literal("Protect your bed, destroy the others!"), 0, 30, 10);
            player.playNotifySound(SoundEvents.ENDER_DRAGON_GROWL, SoundSource.MASTER, 0.5F, 1.0F);
        }
        GameSettings settings = GameSettings.get();
        for (RandomizerSource source : RandomizerSource.values()) refillTicks.put(source, settings.refillSeconds(source) * 20);
        refillAreas.clear();
        for (BoundingBox box : selectionBoxes(arena)) {
            RefillArea area = RefillArea.scan(arena, box);
            if (area != null) refillAreas.add(area);
        }
        updateDisplays(arena);
        checkWin(server);
    }

    private void tickRunning(MinecraftServer server, ServerLevel arena) {
        GameSettings settings = GameSettings.get();
        for (PlayerState state : players.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(state.id);
            if (player == null || !player.isAlive()) continue;
            if (state.status == Status.ALIVE && player.level() == arena && !player.isSpectator() && player.getY() < VOID_Y) {
                player.hurt(player.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
            } else if (state.status == Status.RESPAWNING) {
                if (state.respawnTicks > 0 && state.respawnTicks % 20 == 0) {
                    title(player, Component.literal("YOU DIED!").withStyle(ChatFormatting.RED),
                            Component.literal("Respawning in " + state.respawnTicks / 20 + "s").withStyle(ChatFormatting.YELLOW), 0, 25, 0);
                    player.playNotifySound(SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
                }
                if (--state.respawnTicks <= 0) {
                    state.status = Status.ALIVE;
                    spawnAtBase(player, arena, teams.get(state.team));
                    title(player, Component.literal("RESPAWNED!").withStyle(ChatFormatting.GREEN), Component.empty(), 0, 20, 10);
                    player.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
                }
            }
        }

        if (ticks % 10 == 0) {
            for (TeamState team : teams.values()) {
                if (team.bedAlive && !(arena.getBlockState(team.bedHead).getBlock() instanceof TeamBedBlock)) bedDestroyed(server, team, null);
            }
        }

        for (RandomizerSource source : RandomizerSource.values()) {
            int left = refillTicks.getOrDefault(source, 0) - 1;
            if (left <= 0) {
                for (RefillArea area : refillAreas) {
                    if (area.refill(arena, source) > 0) area.playRefillSound(arena);
                }
                left = settings.refillSeconds(source) * 20;
            }
            refillTicks.put(source, left);
        }
        if (ticks % 20 == 0) updateDisplays(arena);
        if (ticks % 600 == 0) Arena.applyWorldRules(server);
    }

    /** The settings screen's "Refill now": refills that kind everywhere and restarts its timer. */
    public int refillNow(MinecraftServer server, RandomizerSource source) {
        ServerLevel arena = Arena.level(server);
        if (arena == null) return 0;
        int placed = 0;
        for (RefillArea area : refillAreas) {
            int areaPlaced = area.refill(arena, source);
            if (areaPlaced > 0) area.playRefillSound(arena);
            placed += areaPlaced;
        }
        refillTicks.put(source, GameSettings.get().refillSeconds(source) * 20);
        updateDisplays(arena);
        return placed;
    }

    private void updateDisplays(ServerLevel arena) {
        Map<RandomizerSource, Integer> seconds = new EnumMap<>(RandomizerSource.class);
        refillTicks.forEach((source, left) -> seconds.put(source, (left + 19) / 20));
        for (RefillArea area : refillAreas) area.updateDisplay(arena, seconds);
    }

    private static List<BoundingBox> selectionBoxes(ServerLevel arena) {
        List<BoundingBox> boxes = new ArrayList<>();
        for (BlockPos pos : SelectionTracker.get(arena).positions()) {
            if (arena.getBlockEntity(pos) instanceof SelectionBlockEntity selection && selection.worldBox() != null) {
                boxes.add(selection.worldBox());
            }
        }
        return boxes;
    }

    // ------------------------------------------------------------------------------------------
    // Events

    public void onDeath(ServerPlayer victim) {
        PlayerState state = players.get(victim.getUUID());
        if (phase != Phase.RUNNING || state == null || state.status != Status.ALIVE) return;
        MinecraftServer server = victim.getServer();
        TeamState team = teams.get(state.team);
        boolean finalKill = !team.bedAlive;

        addStat(state.id, state.name, stats -> stats.deaths++);
        LivingEntity credit = victim.getKillCredit();
        if (credit instanceof ServerPlayer killer && killer != victim && players.containsKey(killer.getUUID())) {
            addStat(killer.getUUID(), killer.getGameProfile().getName(), stats -> {
                stats.kills++;
                if (finalKill) stats.finalKills++;
            });
            killer.playNotifySound(SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 1.0F, 1.0F);
            if (finalKill) killer.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7F, 1.2F);
        }
        if (finalKill) victim.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.4F, 1.0F);

        if (!finalKill) {
            state.status = Status.RESPAWNING;
            state.respawnTicks = GameSettings.get().respawnSeconds * 20;
        } else {
            state.status = Status.ELIMINATED;
            broadcast(server, teamName(team.color).append(Component.literal(" " + state.name + " was eliminated! ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("FINAL KILL!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
        }
        checkWin(server);
    }

    public void onRespawn(ServerPlayer player) {
        if (phase == Phase.LOBBY) return;
        if (hosts.contains(player.getUUID())) {
            toWaitingSpot(player);
            return;
        }
        PlayerState state = players.get(player.getUUID());
        if (state == null) return;
        if (state.status == Status.RESPAWNING || state.status == Status.ELIMINATED) toWaitingSpot(player);
        if (state.status == Status.ELIMINATED) {
            title(player, Component.literal("ELIMINATED!").withStyle(ChatFormatting.RED), Component.literal("Your bed is gone. You are now a spectator."), 5, 60, 10);
        }
    }

    public void onLogout(ServerPlayer player) {
        PlayerState state = players.get(player.getUUID());
        if (phase == Phase.LOBBY || state == null) return;
        if (state.status == Status.ALIVE || state.status == Status.RESPAWNING) {
            state.status = Status.DISCONNECTED;
            broadcast(player.getServer(), teamName(state.team).append(Component.literal(" " + state.name + " disconnected.").withStyle(ChatFormatting.GRAY)));
        }
        checkWin(player.getServer());
    }

    public void onLogin(ServerPlayer player) {
        if (phase == Phase.LOBBY) {
            // still a spectator from a round that was cut off (crash / quit): back to normal in the lobby
            if (player.isSpectator() && Arena.isArena(player.level())) Arena.teleportToLobby(player);
            return;
        }
        if (hosts.contains(player.getUUID())) {
            toWaitingSpot(player);
            return;
        }
        PlayerState state = players.get(player.getUUID());
        if (state == null || phase == Phase.ENDING) return;
        if (phase == Phase.COUNTDOWN) {
            state.status = Status.ALIVE; // spawned at the base when the countdown ends
            return;
        }
        if (state.status != Status.DISCONNECTED) {
            if (state.status == Status.ELIMINATED) toWaitingSpot(player);
            return;
        }
        toWaitingSpot(player);
        if (teams.get(state.team).bedAlive) {
            state.status = Status.RESPAWNING;
            state.respawnTicks = GameSettings.get().respawnSeconds * 20;
            broadcast(player.getServer(), teamName(state.team).append(Component.literal(" " + state.name + " reconnected.").withStyle(ChatFormatting.GRAY)));
        } else {
            state.status = Status.ELIMINATED;
            player.sendSystemMessage(Component.literal("Your bed was destroyed while you were away. You are a spectator.").withStyle(ChatFormatting.RED));
        }
    }

    /** Returns true when the break must be cancelled (a player's own bed). */
    public boolean onBedBreak(ServerPlayer breaker, BlockState state) {
        if (phase != Phase.RUNNING || !(state.getBlock() instanceof TeamBedBlock bed) || !Arena.isArena(breaker.level())) return false;
        TeamState team = teams.get(bed.getColor());
        if (team == null || !team.bedAlive) return false;
        PlayerState breakerState = players.get(breaker.getUUID());
        if (breakerState != null && breakerState.team == team.color) {
            breaker.displayClientMessage(Component.literal("You can't break your own bed!").withStyle(ChatFormatting.RED), true);
            breaker.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 1.0F, 1.0F);
            return true;
        }
        bedDestroyed(breaker.getServer(), team, breaker);
        return false;
    }

    private void bedDestroyed(MinecraftServer server, TeamState team, @Nullable ServerPlayer breaker) {
        team.bedAlive = false;
        if (breaker != null && players.containsKey(breaker.getUUID())) {
            addStat(breaker.getUUID(), breaker.getGameProfile().getName(), stats -> stats.bedsBroken++);
        }
        MutableComponent message = Component.literal("BED DESTRUCTION > ").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
                .append(teamName(team.color)).append(Component.literal(" Bed was destroyed").withStyle(ChatFormatting.GRAY));
        if (breaker != null) message.append(Component.literal(" by " + breaker.getGameProfile().getName()).withStyle(ChatFormatting.GRAY));
        broadcast(server, message.append(Component.literal("!").withStyle(ChatFormatting.GRAY)));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (team.members.contains(player.getUUID())) {
                title(player, Component.literal("BED DESTROYED!").withStyle(ChatFormatting.RED), Component.literal("You will no longer respawn!"), 5, 50, 10);
                player.playNotifySound(SoundEvents.WITHER_DEATH, SoundSource.HOSTILE, 0.6F, 1.0F);
            } else if (players.containsKey(player.getUUID()) || hosts.contains(player.getUUID())) {
                player.playNotifySound(SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 0.4F, 1.2F);
            }
        }
    }

    private void checkWin(MinecraftServer server) {
        if (phase != Phase.RUNNING || teams.size() < 2) return;
        List<TeamState> remaining = teams.values().stream()
                .filter(team -> team.members.stream().map(players::get)
                        .anyMatch(state -> state.status == Status.ALIVE || state.status == Status.RESPAWNING))
                .toList();
        if (remaining.size() > 1) return;

        lastWinner = remaining.isEmpty() ? null : remaining.get(0).color;
        phase = Phase.ENDING;
        endTicks = END_TICKS;
        roundsPlayed++;
        if (lastWinner != null) {
            for (UUID id : teams.get(lastWinner).members) addStat(id, players.get(id).name, stats -> stats.wins++);
        }

        Component title = lastWinner == null ? Component.literal("GAME OVER").withStyle(ChatFormatting.RED)
                : teamName(lastWinner).append(Component.literal(" WINS!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (PlayerState state : players.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(state.id);
            if (player == null) continue;
            boolean won = state.team == lastWinner;
            title(player, title, Component.literal(won ? "VICTORY!" : "GAME OVER").withStyle(won ? ChatFormatting.GOLD : ChatFormatting.GRAY), 10, 80, 20);
            if (won) {
                player.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.0F, 1.0F);
            } else {
                player.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.MASTER, 1.0F, 0.5F);
            }
        }
        for (UUID id : hosts) {
            ServerPlayer host = server.getPlayerList().getPlayer(id);
            if (host != null) host.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.0F, 1.0F);
        }
        broadcast(server, Component.literal("Bed Wars is over! ").withStyle(ChatFormatting.YELLOW).append(title));
        broadcast(server, topList("Top kills", roundStats, stats -> stats.kills));
        broadcast(server, topList("Top deaths", roundStats, stats -> stats.deaths));
        sendRoundControls(server);

        ServerLevel arena = Arena.level(server);
        if (arena != null) refillAreas.forEach(area -> area.removeDisplay(arena));
    }

    /** [Next round] / [End round] for the hosts, or for the operator who started the round when there is no host. */
    private void sendRoundControls(MinecraftServer server) {
        List<ServerPlayer> targets = new ArrayList<>();
        for (GameSettings.Registered registered : GameSettings.get().players()) {
            ServerPlayer host = registered.host() ? server.getPlayerList().getPlayer(registered.id()) : null;
            if (host != null) targets.add(host);
        }
        if (targets.isEmpty() && starter != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(starter);
            if (player != null) targets.add(player);
        }
        MutableComponent next = Component.literal("[Next round]").withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/bwr nextround"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Start another round (new teams)"))));
        MutableComponent end = Component.literal("[End round]").withStyle(style -> style.withColor(ChatFormatting.RED).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/bwr endround"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Stop here and show everyone's stats from all rounds"))));
        for (ServerPlayer target : targets) {
            target.sendSystemMessage(Component.literal("Round " + roundsPlayed + " is over! ").withStyle(ChatFormatting.YELLOW)
                    .append(next).append(Component.literal("  ")).append(end));
            target.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 1.0F, 1.0F);
        }
    }

    private void launchFireworks(MinecraftServer server, ServerLevel arena) {
        if (lastWinner == null) return;
        TeamState team = teams.get(lastWinner);
        if (team == null) return;
        for (UUID id : team.members) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.level() != arena) continue;
            ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
            CompoundTag fireworks = rocket.getOrCreateTagElement("Fireworks");
            fireworks.putByte("Flight", (byte) 1);
            CompoundTag explosion = new CompoundTag();
            explosion.putByte("Type", (byte) ThreadLocalRandom.current().nextInt(5));
            explosion.putIntArray("Colors", new int[]{lastWinner.getFireworkColor(), DyeColor.WHITE.getFireworkColor()});
            explosion.putBoolean("Trail", true);
            explosion.putBoolean("Flicker", true);
            ListTag explosions = new ListTag();
            explosions.add(explosion);
            fireworks.put("Explosions", explosions);
            arena.addFreshEntity(new FireworkRocketEntity(arena, player.getX(), player.getY() + 1, player.getZ(), rocket));
        }
    }

    private void finish(MinecraftServer server) {
        ServerLevel arena = Arena.level(server);
        List<UUID> everyone = new ArrayList<>(players.keySet());
        everyone.addAll(hosts);
        for (UUID id : everyone) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) continue;
            resetPlayer(player);
            if (arena != null) Arena.teleport(player, arena);
        }
        if (arena != null) refillAreas.forEach(area -> area.removeDisplay(arena));
        server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(false, server);
        if (ReplayRecorder.get().isRecording()) ReplayRecorder.get().stop(server);
        clear();
        phase = Phase.LOBBY;
    }

    // ------------------------------------------------------------------------------------------
    // Helpers

    private void clear() {
        teams.clear();
        players.clear();
        hosts.clear();
        refillAreas.clear();
        refillTicks.clear();
        ticks = 0;
    }

    private void addStat(UUID id, String name, Consumer<Stats> change) {
        change.accept(roundStats.computeIfAbsent(id, key -> new Stats(name)));
        change.accept(sessionStats.computeIfAbsent(id, key -> new Stats(name)));
    }

    private static Component topList(String title, Map<UUID, Stats> stats, ToIntFunction<Stats> value) {
        MutableComponent text = Component.literal(title + ":").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        List<Stats> top = stats.values().stream().filter(entry -> value.applyAsInt(entry) > 0)
                .sorted(Comparator.comparingInt(value).reversed()).limit(5).toList();
        if (top.isEmpty()) {
            return text.append(Component.literal(" -").withStyle(ChatFormatting.GRAY));
        }
        for (int i = 0; i < top.size(); i++) {
            text.append(Component.literal("\n " + (i + 1) + ". ").withStyle(ChatFormatting.GRAY).withStyle(style -> style.withBold(false)))
                    .append(Component.literal(top.get(i).name).withStyle(style -> style.withColor(ChatFormatting.WHITE).withBold(false)))
                    .append(Component.literal(" - " + value.applyAsInt(top.get(i))).withStyle(style -> style.withColor(ChatFormatting.YELLOW).withBold(false)));
        }
        return text;
    }

    private static Component statsCard(Stats stats) {
        String kd = String.format(Locale.ROOT, "%.2f", stats.deaths == 0 ? stats.kills : stats.kills / (double) stats.deaths);
        return Component.literal(stats.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("\nKills: " + stats.kills + "\nFinal kills: " + stats.finalKills + "\nDeaths: " + stats.deaths
                        + "\nK/D: " + kd + "\nBeds broken: " + stats.bedsBroken + "\nWins: " + stats.wins)
                        .withStyle(style -> style.withColor(ChatFormatting.WHITE).withBold(false)));
    }

    private static void resetPlayer(ServerPlayer player) {
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.removeAllEffects();
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        player.clearFire();
    }

    private static void spawnAtBase(ServerPlayer player, ServerLevel arena, TeamState team) {
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.clearFire();
        player.fallDistance = 0;
        player.teleportTo(arena, team.spawn.x, team.spawn.y, team.spawn.z, team.spawnYaw, 0);
    }

    private static void toWaitingSpot(ServerPlayer player) {
        ServerLevel arena = Arena.level(player.getServer());
        if (arena == null) return;
        player.setGameMode(GameType.SPECTATOR);
        player.teleportTo(arena, WAITING_SPOT.x, WAITING_SPOT.y, WAITING_SPOT.z, player.getYRot(), player.getXRot());
    }

    /** The first spot near {@code pos} (a few blocks up or down from the bed) with ground below and room for a player. */
    private static BlockPos standableSpot(ServerLevel level, BlockPos pos, int bedY) {
        for (int y = bedY + 3; y >= bedY - 3; y--) {
            BlockPos spot = pos.atY(y);
            if (!level.getBlockState(spot.below()).getCollisionShape(level, spot.below()).isEmpty()
                    && level.getBlockState(spot).getCollisionShape(level, spot).isEmpty()
                    && level.getBlockState(spot.above()).getCollisionShape(level, spot.above()).isEmpty()) {
                return spot;
            }
        }
        return pos.atY(bedY);
    }

    private static void removeBed(ServerLevel level, BlockPos head) {
        BlockState state = level.getBlockState(head);
        if (!(state.getBlock() instanceof BedBlock)) return;
        level.removeBlock(head.relative(state.getValue(BedBlock.FACING).getOpposite()), false);
        level.removeBlock(head, false);
    }

    private void forEachOnline(MinecraftServer server, Consumer<ServerPlayer> action) {
        for (PlayerState state : players.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(state.id);
            if (player != null) action.accept(player);
        }
    }

    /** One line per team ("Lime Alex, Steve"), then the hosts. */
    private Component teamsText() {
        MutableComponent text = Component.literal("Teams:").withStyle(ChatFormatting.GRAY);
        for (TeamState team : teams.values()) {
            String names = team.members.stream().map(id -> players.get(id).name).collect(Collectors.joining(", "));
            text.append(Component.literal("\n ")).append(teamName(team.color)).append(Component.literal(" " + names).withStyle(ChatFormatting.WHITE));
        }
        if (!hosts.isEmpty()) {
            String names = hosts.stream().map(id -> {
                GameSettings.Registered registered = GameSettings.get().player(id);
                return registered == null ? "?" : registered.name();
            }).collect(Collectors.joining(", "));
            text.append(Component.literal("\n Host ").withStyle(ChatFormatting.GOLD)).append(Component.literal(names).withStyle(ChatFormatting.WHITE));
        }
        return text;
    }

    public static MutableComponent teamName(DyeColor color) {
        return Component.translatable("color.minecraft." + color.getName()).withStyle(style -> style.withColor(color.getTextColor()).withBold(true));
    }

    private static void title(ServerPlayer player, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        player.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    private static void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    /** Tells a player they were registered. */
    public static void notifyRegistered(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("You are registered for Bed Wars!").withStyle(ChatFormatting.YELLOW));
        player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 1.2F);
    }

    /** [Teleport all to lobby] (choose a map first) for the hosts and whoever registered the players. */
    public static void sendLobbyLink(MinecraftServer server, @Nullable ServerPlayer registeredBy) {
        Set<ServerPlayer> targets = new LinkedHashSet<>();
        for (GameSettings.Registered registered : GameSettings.get().players()) {
            ServerPlayer host = registered.host() ? server.getPlayerList().getPlayer(registered.id()) : null;
            if (host != null) targets.add(host);
        }
        if (registeredBy != null) targets.add(registeredBy);
        MutableComponent link = Component.literal("[Teleport all to lobby]").withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/bwr lobbyall"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Choose a map and bring every registered player to the lobby"))));
        int count = GameSettings.get().players().size();
        for (ServerPlayer target : targets) {
            target.sendSystemMessage(Component.literal(count + " player(s) registered. ").withStyle(ChatFormatting.YELLOW).append(link));
        }
    }

    // ------------------------------------------------------------------------------------------
    // State for commands, the settings screen and tests

    public Phase phase() {
        return phase;
    }

    public boolean isActive() {
        return phase != Phase.LOBBY;
    }

    @Nullable
    public Status statusOf(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? null : state.status;
    }

    @Nullable
    public DyeColor teamOf(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? null : state.team;
    }

    public boolean isHostInRound(UUID player) {
        return hosts.contains(player);
    }

    @Nullable
    public BlockPos bedOf(DyeColor color) {
        TeamState team = teams.get(color);
        return team == null ? null : team.bedHead;
    }

    @Nullable
    public Vec3 spawnOf(DyeColor color) {
        TeamState team = teams.get(color);
        return team == null ? null : team.spawn;
    }

    public boolean bedAlive(DyeColor color) {
        TeamState team = teams.get(color);
        return team != null && team.bedAlive;
    }

    public List<RefillArea> refillAreas() {
        return Collections.unmodifiableList(refillAreas);
    }

    /** Whether this entity is the countdown text of a refill area of the running round. */
    public boolean isCurrentRefillDisplay(UUID entity) {
        return refillAreas.stream().anyMatch(area -> entity.equals(area.displayId()));
    }

    @Nullable
    public DyeColor lastWinner() {
        return lastWinner;
    }

    @Nullable
    public Stats roundStatsOf(UUID player) {
        return roundStats.get(player);
    }

    @Nullable
    public Stats sessionStatsOf(UUID player) {
        return sessionStats.get(player);
    }

    public int roundsPlayed() {
        return roundsPlayed;
    }
}
