package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.replay.ReplayData;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;

public final class ModNetwork {
    private static final String PROTOCOL = "2";
    /** Vanilla limit for a clientbound custom payload is 1 MiB; keep some headroom. */
    private static final int MAX_PAYLOAD_BYTES = 1_000_000;

    // Vanilla clients may still join (they just can't watch or record replays).
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(BedwarsRandomizer.MOD_ID, "main"),
            () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL),
            NetworkRegistry.acceptMissingOr(PROTOCOL));

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(PlayReplayPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PlayReplayPacket::encode)
                .decoder(PlayReplayPacket::decode)
                .consumerMainThread(PlayReplayPacket::handle)
                .add();
        CHANNEL.messageBuilder(StopReplayPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StopReplayPacket::encode)
                .decoder(StopReplayPacket::decode)
                .consumerMainThread(StopReplayPacket::handle)
                .add();
        CHANNEL.messageBuilder(ClipRequestPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ClipRequestPacket::encode)
                .decoder(ClipRequestPacket::decode)
                .consumerMainThread(ClipRequestPacket::handle)
                .add();
        CHANNEL.messageBuilder(ClipChunkPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ClipChunkPacket::encode)
                .decoder(ClipChunkPacket::decode)
                .consumerMainThread(ClipChunkPacket::handle)
                .add();
    }

    /** Whether this player's client has the mod (fake players and vanilla clients don't). */
    public static boolean hasMod(ServerPlayer player) {
        try {
            return player.connection != null && CHANNEL.isRemotePresent(player.connection.connection);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void sendReplay(ServerPlayer player, ReplayData data) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PlayReplayPacket(data));
    }

    public static void sendStop(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StopReplayPacket());
    }

    public static void sendClipRequest(ServerPlayer player, ClipRequestPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendClipChunk(ClipChunkPacket packet) {
        CHANNEL.sendToServer(packet);
    }

    /** Returns the replay, a slimmer copy with only killer and victim, or null if even that is too large. */
    @Nullable
    public static ReplayData fitToPacket(ReplayData data) {
        if (encodedSize(data) <= MAX_PAYLOAD_BYTES) return data;
        ReplayData slim = data.onlyKillerAndVictim();
        return encodedSize(slim) <= MAX_PAYLOAD_BYTES ? slim : null;
    }

    private static int encodedSize(ReplayData data) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            data.write(buf);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    private ModNetwork() {}
}
