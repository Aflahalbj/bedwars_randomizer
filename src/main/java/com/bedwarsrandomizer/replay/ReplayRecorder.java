package com.bedwarsrandomizer.replay;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.BwrConfig;
import com.bedwarsrandomizer.command.BwrCommand;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Keeps a rolling buffer of the last few seconds of every player's movement while a game is running.
 * When a player kills another player the buffer (plus a short tail after the death) becomes a {@link ReplayData}.
 */
public final class ReplayRecorder {
    private static final ReplayRecorder INSTANCE = new ReplayRecorder();

    public static ReplayRecorder get() {
        return INSTANCE;
    }

    private record Sample(ResourceKey<Level> dimension, String name, ActorFrame frame, ItemStack[] equipment) {}

    private record RawChange(ResourceKey<Level> dimension, BlockPos pos, BlockState oldState, BlockState newState) {}

    private record Snapshot(long tick, Map<UUID, Sample> samples, List<RawChange> changes) {}

    private record DimPos(ResourceKey<Level> dimension, BlockPos pos) {}

    private record PendingKill(UUID replayId, List<Snapshot> snapshots, long deathTick,
                               UUID killerId, String killerName, UUID victimId, String victimName,
                               ResourceKey<Level> dimension, Vec3 victimPos, KillCause cause, ItemStack weapon) {}

    private boolean recording;
    private final ArrayDeque<Snapshot> buffer = new ArrayDeque<>();
    private final List<PendingKill> pending = new ArrayList<>();
    private final Map<UUID, ItemStack[]> lastEquipment = new HashMap<>();
    private final Map<UUID, Long> lastSwingTick = new HashMap<>();
    /** Critical / enchanted hit flags announced for a player this tick, waiting for the damage to land. */
    private final Map<UUID, Integer> pendingHits = new HashMap<>();
    /** Critical / enchanted hits that landed on a player this tick. */
    private final Map<UUID, Integer> hitFlags = new HashMap<>();
    /** Blocks touched during the current tick, mapped to their state before the first change. */
    private final Map<DimPos, BlockState> touchedBlocks = new LinkedHashMap<>();

    private ReplayRecorder() {}

    public boolean isRecording() {
        return recording;
    }

    public void start() {
        reset();
        recording = true;
    }

    /** Stops recording. Kills still waiting for their post-death tail are saved with what they have. */
    public void stop(MinecraftServer server) {
        for (PendingKill kill : pending) {
            finish(server, kill);
        }
        reset();
    }

    public void reset() {
        recording = false;
        buffer.clear();
        pending.clear();
        lastEquipment.clear();
        lastSwingTick.clear();
        pendingHits.clear();
        hitFlags.clear();
        touchedBlocks.clear();
    }

    /** Remember the state of a block that is about to change (or just changed) this tick. */
    public void markBlock(Level level, BlockPos pos, BlockState before) {
        if (!recording || level.isClientSide()) return;
        touchedBlocks.putIfAbsent(new DimPos(level.dimension(), pos.immutable()), before);
    }

    /** An attack on {@code target} is going to be critical and/or enchanted; it only counts if the damage lands. */
    public void markPendingHit(UUID target, int flags) {
        if (recording) pendingHits.merge(target, flags, (a, b) -> a | b);
    }

    /** Damage landed on {@code target}: its announced critical/enchanted hit becomes part of this tick. */
    public void confirmHit(UUID target) {
        Integer flags = pendingHits.remove(target);
        if (flags != null) hitFlags.merge(target, flags, (a, b) -> a | b);
    }

    public void onKill(ServerPlayer victim, ServerPlayer killer, DamageSource source) {
        if (!recording) return;
        UUID replayId = UUID.randomUUID();
        KillCause cause = KillCause.of(source, killer);
        ItemStack weapon = killer.getMainHandItem().copy();
        pending.add(new PendingKill(replayId, new ArrayList<>(buffer), victim.getServer().getTickCount(),
                killer.getUUID(), killer.getGameProfile().getName(),
                victim.getUUID(), victim.getGameProfile().getName(),
                victim.level().dimension(), victim.position(), cause, weapon));
        // the server's own recording is the fallback; a watching client's recording replaces it when it arrives
        ClipAssembler.requestClip(victim.getServer(), replayId, killer, victim, cause, weapon);
    }

    public void tick(MinecraftServer server) {
        if (!recording) return;

        List<RawChange> changes = new ArrayList<>();
        for (Map.Entry<DimPos, BlockState> entry : touchedBlocks.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey().dimension());
            if (level == null) continue;
            BlockState now = level.getBlockState(entry.getKey().pos());
            if (now != entry.getValue()) {
                changes.add(new RawChange(entry.getKey().dimension(), entry.getKey().pos(), entry.getValue(), now));
            }
        }
        touchedBlocks.clear();

        Map<UUID, Sample> samples = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) continue;
            samples.put(player.getUUID(), new Sample(player.level().dimension(), player.getGameProfile().getName(),
                    ActorFrame.capture(player, swingStarted(player, server.getTickCount()),
                            hitFlags.getOrDefault(player.getUUID(), 0)), equipmentOf(player)));
        }
        hitFlags.clear();
        pendingHits.clear();

        Snapshot snapshot = new Snapshot(server.getTickCount(), samples, changes);
        buffer.addLast(snapshot);
        int maxFrames = BwrConfig.REPLAY_SECONDS.get() * 20 + 1;
        while (buffer.size() > maxFrames) {
            buffer.removeFirst();
        }

        int tail = BwrConfig.POST_DEATH_TICKS.get();
        Iterator<PendingKill> it = pending.iterator();
        while (it.hasNext()) {
            PendingKill kill = it.next();
            kill.snapshots().add(snapshot);
            if (snapshot.tick() >= kill.deathTick() + tail) {
                it.remove();
                finish(server, kill);
            }
        }
    }

    /**
     * Whether an arm swing started this tick. {@code swing()} sets swingTime to -1 and the player's next tick moves it
     * to 0, so a new swing shows up as swinging with swingTime ≤ 0 — possibly on two consecutive ticks.
     */
    private boolean swingStarted(ServerPlayer player, long tick) {
        if (!player.swinging || player.swingTime > 0) return false;
        Long last = lastSwingTick.put(player.getUUID(), tick);
        return last == null || last < tick - 1;
    }

    private ItemStack[] equipmentOf(ServerPlayer player) {
        ItemStack[] current = new ItemStack[ReplayData.SLOTS.length];
        for (int i = 0; i < current.length; i++) {
            current[i] = player.getItemBySlot(ReplayData.SLOTS[i]);
        }
        ItemStack[] last = lastEquipment.get(player.getUUID());
        if (last != null) {
            boolean same = true;
            for (int i = 0; i < current.length && same; i++) {
                same = ItemStack.isSameItemSameTags(last[i], current[i]);
            }
            if (same) return last;
        }
        ItemStack[] copy = new ItemStack[current.length];
        for (int i = 0; i < current.length; i++) {
            copy[i] = current[i].copyWithCount(1);
        }
        lastEquipment.put(player.getUUID(), copy);
        return copy;
    }

    // ------------------------------------------------------------------------------------------
    // Building the replay

    private void finish(MinecraftServer server, PendingKill kill) {
        List<Snapshot> snapshots = kill.snapshots();
        int frameCount = snapshots.size();
        if (frameCount == 0) return;

        int deathFrame = frameCount - 1;
        for (int i = 0; i < frameCount; i++) {
            if (snapshots.get(i).tick() >= kill.deathTick()) {
                deathFrame = i;
                break;
            }
        }

        double range = BwrConfig.REPLAY_ACTOR_RANGE.get();
        double rangeSq = range * range;

        LinkedHashMap<UUID, String> actorIds = new LinkedHashMap<>();
        actorIds.put(kill.killerId(), kill.killerName());
        actorIds.put(kill.victimId(), kill.victimName());
        for (Snapshot snapshot : snapshots) {
            Sample killer = snapshot.samples().get(kill.killerId());
            Sample victim = snapshot.samples().get(kill.victimId());
            for (Map.Entry<UUID, Sample> entry : snapshot.samples().entrySet()) {
                Sample sample = entry.getValue();
                if (actorIds.containsKey(entry.getKey()) || !sample.dimension().equals(kill.dimension())) continue;
                if (isNear(sample, victim, rangeSq) || isNear(sample, killer, rangeSq)) {
                    actorIds.put(entry.getKey(), sample.name());
                }
            }
        }

        List<ReplayData.Actor> actors = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : actorIds.entrySet()) {
            UUID id = entry.getKey();
            ActorFrame[] frames = new ActorFrame[frameCount];
            List<ItemStack[]> equipment = new ArrayList<>();
            IdentityHashMap<ItemStack[], Integer> equipmentIndex = new IdentityHashMap<>();
            boolean any = false;

            for (int i = 0; i < frameCount; i++) {
                Sample sample = snapshots.get(i).samples().get(id);
                if (sample == null || !sample.dimension().equals(kill.dimension())) continue;
                int index = equipmentIndex.computeIfAbsent(sample.equipment(), set -> {
                    equipment.add(set);
                    return equipment.size() - 1;
                });
                frames[i] = sample.frame().withEquipment(index);
                any = true;
            }

            if (id.equals(kill.victimId())) {
                if (!any || !fixVictimDeath(frames, deathFrame)) {
                    BedwarsRandomizer.LOGGER.warn("Kill replay discarded: no frames for victim {}", kill.victimName());
                    return;
                }
            }
            if (any) {
                actors.add(new ReplayData.Actor(id, entry.getValue(), equipment, frames));
            }
        }

        double blockRangeSq = (range + 16) * (range + 16);
        List<BlockChange> blockChanges = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            for (RawChange change : snapshots.get(i).changes()) {
                if (change.dimension().equals(kill.dimension())
                        && change.pos().distToCenterSqr(kill.victimPos()) <= blockRangeSq) {
                    blockChanges.add(new BlockChange(i, change.pos(), change.oldState(), change.newState()));
                }
            }
        }

        ReplayData data = new ReplayData(kill.replayId(), System.currentTimeMillis(),
                kill.killerId(), kill.killerName(), kill.victimId(), kill.victimName(),
                kill.dimension(), kill.cause(), kill.weapon(), frameCount, deathFrame, actors, blockChanges, false);
        int number = ReplayStorage.add(data);
        ReplayDebugDump.write(server, data);
        ClipAssembler.serverReplayFinished(server, data);

        BedwarsRandomizer.LOGGER.info("Saved kill replay: {} killed {} #{} ({} frames, {} players, {} block changes)",
                data.killerName, data.victimName, number, frameCount, actors.size(), blockChanges.size());
        announce(server, data, number);
    }

    /** Makes the victim lie dead from the death frame on, even if they respawned during the tail. */
    private static boolean fixVictimDeath(ActorFrame[] frames, int deathFrame) {
        ActorFrame last = null;
        for (int i = deathFrame; i >= 0 && last == null; i--) {
            last = frames[i];
        }
        if (last == null) return false;

        for (int i = deathFrame; i < frames.length; i++) {
            ActorFrame frame = frames[i];
            if (i == deathFrame && frame != null) {
                // keep the killing blow as recorded (hurt, critical hit), only make sure the victim is dead
                frames[i] = frame.health() > 0 ? frame.asDead(0).withEventsOf(frame) : frame;
                last = frame;
                continue;
            }
            boolean respawned = frame != null && i > deathFrame && frame.health() > 0;
            if (frame != null && !respawned) {
                last = frame;
            }
            frames[i] = last.asDead(i - deathFrame);
        }
        return true;
    }

    private static boolean isNear(Sample sample, Sample other, double rangeSq) {
        if (other == null || !sample.dimension().equals(other.dimension())) return false;
        double dx = sample.frame().x() - other.frame().x();
        double dy = sample.frame().y() - other.frame().y();
        double dz = sample.frame().z() - other.frame().z();
        return dx * dx + dy * dy + dz * dz <= rangeSq;
    }

    private static void announce(MinecraftServer server, ReplayData data, int number) {
        String command = BwrCommand.watchCommand("@s", data, number);
        Component message = Component.literal("[BWR] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Kill replay saved: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(data.killerName).withStyle(ChatFormatting.RED))
                .append(Component.literal(" ⚔ ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(data.victimName).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" [Watch]").withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) {
                player.sendSystemMessage(message);
            }
        }
    }
}
