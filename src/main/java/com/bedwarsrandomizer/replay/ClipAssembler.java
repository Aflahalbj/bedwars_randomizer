package com.bedwarsrandomizer.replay;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.BwrConfig;
import com.bedwarsrandomizer.network.ClipChunkPacket;
import com.bedwarsrandomizer.network.ClipRequestPacket;
import com.bedwarsrandomizer.network.ModNetwork;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Replay-Mod-style recordings: when a kill happens, the client of a player who watched it (killer, else victim,
 * else the nearest player) uploads what it saw. That recording replaces the server's fallback replay once it
 * arrives, merged with what only the server knows (block changes, critical hits).
 */
public final class ClipAssembler {
    private static final long TIMEOUT_MS = 20_000;

    private static final class Upload {
        final UUID uploader;
        final long startedAt = System.currentTimeMillis();
        byte[][] chunks;
        int received;
        ReplayData serverReplay;
        ReplayData clientReplay;

        Upload(UUID uploader) {
            this.uploader = uploader;
        }
    }

    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();

    public static void requestClip(MinecraftServer server, UUID replayId, ServerPlayer killer, ServerPlayer victim,
                                   KillCause cause, ItemStack weapon) {
        ServerPlayer observer = pickObserver(server, killer, victim);
        if (observer == null) return;

        UPLOADS.put(replayId, new Upload(observer.getUUID()));
        ModNetwork.sendClipRequest(observer, new ClipRequestPacket(replayId,
                killer.getUUID(), killer.getGameProfile().getName(), victim.getUUID(), victim.getGameProfile().getName(),
                cause, weapon, BwrConfig.REPLAY_SECONDS.get() * 20, BwrConfig.POST_DEATH_TICKS.get()));
        BedwarsRandomizer.LOGGER.info("Requested kill recording from {}'s client", observer.getGameProfile().getName());
    }

    /** The killer's client saw the fight exactly as the killer did; otherwise the victim's, otherwise a bystander's. */
    @Nullable
    private static ServerPlayer pickObserver(MinecraftServer server, ServerPlayer killer, ServerPlayer victim) {
        if (ModNetwork.hasMod(killer)) return killer;
        if (ModNetwork.hasMod(victim)) return victim;
        double range = BwrConfig.REPLAY_ACTOR_RANGE.get();
        ServerPlayer best = null;
        double bestDistance = range * range;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == killer || player == victim || player.level() != victim.level() || !ModNetwork.hasMod(player)) {
                continue;
            }
            double distance = player.distanceToSqr(victim);
            if (distance <= bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    public static void serverReplayFinished(MinecraftServer server, ReplayData data) {
        Upload upload = UPLOADS.get(data.replayId);
        if (upload == null) return;
        upload.serverReplay = data;
        tryMerge(server, data.replayId, upload);
    }

    public static void receive(MinecraftServer server, ServerPlayer sender, ClipChunkPacket packet) {
        Upload upload = UPLOADS.get(packet.replayId());
        if (upload == null || !sender.getUUID().equals(upload.uploader)) return;
        if (packet.total() <= 0 || packet.total() > ClipChunkPacket.MAX_CHUNKS
                || packet.index() < 0 || packet.index() >= packet.total()) {
            UPLOADS.remove(packet.replayId());
            return;
        }
        if (upload.chunks == null) upload.chunks = new byte[packet.total()][];
        if (upload.chunks.length != packet.total()) return;
        if (upload.chunks[packet.index()] == null) {
            upload.chunks[packet.index()] = packet.bytes();
            upload.received++;
        }
        if (upload.received < packet.total()) return;

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] chunk : upload.chunks) {
            all.writeBytes(chunk);
        }
        try {
            ReplayData clip = ReplayData.read(new FriendlyByteBuf(Unpooled.wrappedBuffer(all.toByteArray())));
            if (!clip.replayId.equals(packet.replayId()) || clip.frameCount <= 0 || clip.frameCount > 2000) {
                throw new IllegalStateException("clip does not match the request");
            }
            upload.clientReplay = clip;
            tryMerge(server, packet.replayId(), upload);
        } catch (Exception e) {
            BedwarsRandomizer.LOGGER.warn("Discarding kill recording from {}: {}", sender.getGameProfile().getName(), e.toString());
            UPLOADS.remove(packet.replayId());
        }
    }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Upload>> it = UPLOADS.entrySet().iterator();
        while (it.hasNext()) {
            Upload upload = it.next().getValue();
            if (now - upload.startedAt < TIMEOUT_MS) continue;
            it.remove();
            if (upload.clientReplay != null && upload.serverReplay == null) {
                // the server discarded its own recording; the client's one is still better than nothing
                ReplayStorage.add(upload.clientReplay);
                ReplayDebugDump.write(server, upload.clientReplay);
            }
        }
    }

    public static void reset() {
        UPLOADS.clear();
    }

    private static void tryMerge(MinecraftServer server, UUID replayId, Upload upload) {
        if (upload.serverReplay == null || upload.clientReplay == null) return;
        UPLOADS.remove(replayId);

        ReplayData merged = merge(upload.serverReplay, upload.clientReplay);
        ReplayStorage.replace(merged);
        ReplayDebugDump.write(server, merged);

        long players = merged.actors.stream().filter(ReplayData.Actor::isPlayer).count();
        BedwarsRandomizer.LOGGER.info("Kill replay {} ⚔ {} now uses the client recording ({} frames, {} players, {} entities, {} block changes)",
                merged.killerName, merged.victimName, merged.frameCount, players, merged.actors.size() - players,
                merged.blockChanges.size());
    }

    /** Client frames, plus the server's block changes and critical hits moved onto the client's timeline. */
    static ReplayData merge(ReplayData server, ReplayData client) {
        int offset = client.deathFrame - server.deathFrame;

        List<BlockChange> blocks = new ArrayList<>();
        for (BlockChange change : server.blockChanges) {
            int frame = Mth.clamp(change.frame() + offset, 0, client.frameCount - 1);
            blocks.add(new BlockChange(frame, change.pos(), change.oldState(), change.newState()));
        }

        List<ReplayData.Actor> tracks = new ArrayList<>();
        for (ReplayData.Actor track : client.actors) {
            ReplayData.Actor serverActor = track.isPlayer() ? server.actor(track.id) : null;
            if (serverActor == null) {
                tracks.add(track);
                continue;
            }
            ActorFrame[] frames = track.frames.clone();
            for (int f = 0; f < serverActor.frames.length; f++) {
                ActorFrame serverFrame = serverActor.frames[f];
                if (serverFrame == null) continue;
                int hit = serverFrame.flags() & (ActorFrame.FLAG_CRIT | ActorFrame.FLAG_MAGIC_CRIT);
                if (hit == 0) continue;
                // put the sparks on the tick where this client saw the damage
                int target = nearestHurt(frames, f + offset);
                if (target >= 0) frames[target] = frames[target].withFlags(frames[target].flags() | hit);
            }
            tracks.add(new ReplayData.Actor(track.id, track.name, track.type, track.equipment, track.dataSets, frames));
        }

        return new ReplayData(client.replayId, server.createdAt, server.killerId, server.killerName,
                server.victimId, server.victimName, client.dimension, server.cause, server.weapon,
                client.frameCount, client.deathFrame, tracks, blocks, true);
    }

    private static int nearestHurt(ActorFrame[] frames, int around) {
        for (int d = 0; d <= 4; d++) {
            for (int k : new int[]{around - d, around + d}) {
                if (k <= 0 || k >= frames.length || frames[k] == null || frames[k - 1] == null) continue;
                if (frames[k].hurtTime() > frames[k - 1].hurtTime()) return k;
            }
        }
        return around >= 0 && around < frames.length && frames[around] != null ? around : -1;
    }

    private ClipAssembler() {}
}
