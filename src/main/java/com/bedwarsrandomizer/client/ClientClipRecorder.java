package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.BwrConfig;
import com.bedwarsrandomizer.network.ClipChunkPacket;
import com.bedwarsrandomizer.network.ClipRequestPacket;
import com.bedwarsrandomizer.network.ModNetwork;
import com.bedwarsrandomizer.replay.ActorFrame;
import com.bedwarsrandomizer.replay.ReplayData;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Records what this client renders, like Replay Mod: every tick, the exact state of nearby players (including the
 * local player) and projectiles / TNT is kept in a short rolling buffer. When the server reports a kill this client
 * witnessed, the buffer (plus a short tail after the death) is uploaded as the replay of that kill.
 */
@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID, value = Dist.CLIENT)
public final class ClientClipRecorder {

    private record Sample(String name, @Nullable EntityType<?> type, ActorFrame frame,
                          @Nullable ItemStack[] equipment, byte[] data) {}

    private static final class Request {
        final ClipRequestPacket packet;
        int ticksLeft;

        Request(ClipRequestPacket packet) {
            this.packet = packet;
            this.ticksLeft = packet.postFrames();
        }
    }

    private static final ArrayDeque<Map<UUID, Sample>> BUFFER = new ArrayDeque<>();
    private static final Map<UUID, ItemStack[]> LAST_EQUIPMENT = new HashMap<>();
    private static final Map<UUID, byte[]> LAST_DATA = new HashMap<>();
    private static final List<Request> REQUESTS = new ArrayList<>();
    private static ClientLevel recordedLevel;

    public static void request(ClipRequestPacket packet) {
        REQUESTS.add(new Request(packet));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            clear();
            return;
        }
        if (mc.level != recordedLevel) {
            clear();
            recordedLevel = mc.level;
        }
        if (ClientReplayHandler.current() != null) return; // never record a replay that is being watched

        capture(mc);

        Iterator<Request> it = REQUESTS.iterator();
        while (it.hasNext()) {
            Request request = it.next();
            if (--request.ticksLeft <= 0) {
                it.remove();
                upload(mc, request.packet);
            }
        }
    }

    private static void clear() {
        BUFFER.clear();
        LAST_EQUIPMENT.clear();
        LAST_DATA.clear();
        REQUESTS.clear();
        recordedLevel = null;
    }

    // ------------------------------------------------------------------------------------------
    // Capture

    private static void capture(Minecraft mc) {
        double range = BwrConfig.REPLAY_ACTOR_RANGE.get();
        Vec3 center = mc.player.position();
        Map<UUID, Sample> samples = new HashMap<>();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(center) > range * range) continue;
            if (entity instanceof AbstractClientPlayer player) {
                if (player.isSpectator()) continue;
                samples.put(player.getUUID(), new Sample(player.getGameProfile().getName(), null,
                        playerFrame(player), equipmentOf(player), dataOf(player)));
            } else if (isRecorded(entity)) {
                samples.put(entity.getUUID(), new Sample("", entity.getType(), entityFrame(entity), null, dataOf(entity)));
            }
        }

        BUFFER.addLast(samples);
        int maxFrames = BwrConfig.REPLAY_SECONDS.get() * 20 + BwrConfig.POST_DEATH_TICKS.get() + 21;
        while (BUFFER.size() > maxFrames) {
            BUFFER.removeFirst();
        }
    }

    private static boolean isRecorded(Entity entity) {
        return (entity instanceof Projectile && !(entity instanceof FishingHook)) || entity instanceof PrimedTnt;
    }

    /** Everything the renderer uses for a player, as it is at the end of this tick. */
    private static ActorFrame playerFrame(AbstractClientPlayer p) {
        int flags = 0;
        if (p.onGround()) flags |= ActorFrame.FLAG_ON_GROUND;
        if (p.swingingArm == InteractionHand.OFF_HAND) flags |= ActorFrame.FLAG_OFFHAND_SWING;
        return new ActorFrame(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(),
                p.yBodyRot, p.yHeadRot, p.attackAnim, p.walkAnimation.position(), p.walkAnimation.speed(),
                (byte) p.getPose().ordinal(), flags,
                (byte) Math.min(p.hurtTime, 127), (byte) Math.min(p.deathTime, 127),
                p.getHealth(), p.getMaxHealth(), -1, -1);
    }

    private static ActorFrame entityFrame(Entity e) {
        int flags = e.onGround() ? ActorFrame.FLAG_ON_GROUND : 0;
        return new ActorFrame(e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot(),
                e.getYRot(), e.getYRot(), 0, 0, 0, (byte) e.getPose().ordinal(), flags,
                (byte) 0, (byte) 0, 1, 1, -1, -1);
    }

    private static ItemStack[] equipmentOf(AbstractClientPlayer player) {
        ItemStack[] current = new ItemStack[ReplayData.SLOTS.length];
        for (int i = 0; i < current.length; i++) {
            current[i] = player.getItemBySlot(ReplayData.SLOTS[i]);
        }
        ItemStack[] last = LAST_EQUIPMENT.get(player.getUUID());
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
        LAST_EQUIPMENT.put(player.getUUID(), copy);
        return copy;
    }

    /** Synced entity data (pose, flags, health, arrow crit, trident glint, TNT fuse...) in the vanilla packet format. */
    private static byte[] dataOf(Entity entity) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
            if (values != null) {
                for (SynchedEntityData.DataValue<?> value : values) {
                    value.write(buf);
                }
            }
            buf.writeByte(255);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);

            byte[] last = LAST_DATA.get(entity.getUUID());
            if (last != null && Arrays.equals(last, bytes)) return last;
            LAST_DATA.put(entity.getUUID(), bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Upload

    private static void upload(Minecraft mc, ClipRequestPacket request) {
        List<Map<UUID, Sample>> snapshots = new ArrayList<>(BUFFER);
        int keep = Math.min(snapshots.size(), request.preFrames() + request.postFrames() + 1);
        snapshots = snapshots.subList(snapshots.size() - keep, snapshots.size());
        int frameCount = snapshots.size();
        if (frameCount == 0) return;

        int deathFrame = -1;
        int vanishedAt = -1;
        for (int i = 0; i < frameCount && deathFrame < 0; i++) {
            Sample victim = snapshots.get(i).get(request.victimId());
            if (victim == null) continue;
            if (victim.frame().health() <= 0 || victim.frame().deathTime() > 0) {
                deathFrame = i;
            } else if (vanishedAt < 0 && i + 1 < frameCount && !snapshots.get(i + 1).containsKey(request.victimId())) {
                vanishedAt = i;
            }
        }
        // fake players, and players who respawn instantly, disappear the moment they die
        if (deathFrame < 0 && vanishedAt >= 0) deathFrame = vanishedAt;
        if (deathFrame < 0) deathFrame = Math.max(0, frameCount - 1 - request.postFrames());

        LinkedHashMap<UUID, Sample> firstSeen = new LinkedHashMap<>();
        for (Map<UUID, Sample> snapshot : snapshots) {
            snapshot.forEach(firstSeen::putIfAbsent);
        }
        if (!firstSeen.containsKey(request.victimId())) {
            BedwarsRandomizer.LOGGER.info("Kill recording not sent: this client did not see {}", request.victimName());
            return;
        }

        List<ReplayData.Actor> tracks = new ArrayList<>();
        for (Map.Entry<UUID, Sample> entry : firstSeen.entrySet()) {
            UUID id = entry.getKey();
            ActorFrame[] frames = new ActorFrame[frameCount];
            List<ItemStack[]> equipment = new ArrayList<>();
            IdentityHashMap<ItemStack[], Integer> equipmentIndex = new IdentityHashMap<>();
            List<byte[]> dataSets = new ArrayList<>();
            IdentityHashMap<byte[], Integer> dataIndex = new IdentityHashMap<>();

            for (int i = 0; i < frameCount; i++) {
                Sample sample = snapshots.get(i).get(id);
                if (sample == null) continue;
                int eq = sample.equipment() == null ? -1 : equipmentIndex.computeIfAbsent(sample.equipment(), set -> {
                    equipment.add(set);
                    return equipment.size() - 1;
                });
                int ds = dataIndex.computeIfAbsent(sample.data(), bytes -> {
                    dataSets.add(bytes);
                    return dataSets.size() - 1;
                });
                frames[i] = sample.frame().withIndices(eq, ds);
            }
            if (id.equals(request.victimId())) {
                keepVictimDown(frames, deathFrame);
            }
            Sample first = entry.getValue();
            tracks.add(new ReplayData.Actor(id, first.name(), first.type(), equipment, dataSets, frames));
        }

        ReplayData clip = new ReplayData(request.replayId(), System.currentTimeMillis(),
                request.killerId(), request.killerName(), request.victimId(), request.victimName(),
                mc.level.dimension(), request.cause(), request.weapon(), frameCount, deathFrame, tracks, List.of(), true);
        send(clip);
    }

    /**
     * After dying the victim may respawn (same UUID, somewhere else) or vanish without this client ever seeing it
     * dead; keep it lying where it died, playing the death animation.
     */
    private static void keepVictimDown(ActorFrame[] frames, int deathFrame) {
        ActorFrame last = null;
        for (int i = deathFrame; i >= 0 && last == null; i--) {
            last = frames[i];
        }
        if (last == null) return;

        for (int i = deathFrame; i < frames.length; i++) {
            ActorFrame frame = frames[i];
            if (frame != null && (frame.health() <= 0 || frame.deathTime() > 0)) {
                last = frame;
                continue;
            }
            if (i == deathFrame && frame != null) {
                // the killing blow as seen (hurt, sparks), from here on dying
                last = frame.asDead(0).withEventsOf(frame);
            } else {
                last = last.asDead(last.deathTime() + 1);
            }
            frames[i] = last;
        }
    }

    private static void send(ReplayData clip) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            clip.write(buf);
            byte[] all = new byte[buf.readableBytes()];
            buf.readBytes(all);

            int total = (all.length + ClipChunkPacket.CHUNK_BYTES - 1) / ClipChunkPacket.CHUNK_BYTES;
            if (total > ClipChunkPacket.MAX_CHUNKS) {
                BedwarsRandomizer.LOGGER.warn("Kill recording too large to send ({} bytes)", all.length);
                return;
            }
            for (int i = 0; i < total; i++) {
                int from = i * ClipChunkPacket.CHUNK_BYTES;
                int to = Math.min(all.length, from + ClipChunkPacket.CHUNK_BYTES);
                ModNetwork.sendClipChunk(new ClipChunkPacket(clip.replayId, i, total, Arrays.copyOfRange(all, from, to)));
            }
            BedwarsRandomizer.LOGGER.info("Sent kill recording: {} frames, {} tracks, {} bytes",
                    clip.frameCount, clip.actors.size(), all.length);
        } finally {
            buf.release();
        }
    }

    private ClientClipRecorder() {}
}
