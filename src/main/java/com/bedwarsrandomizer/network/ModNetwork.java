package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.game.GameSettings;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
        CHANNEL.messageBuilder(SelectionUpdatePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SelectionUpdatePacket::encode)
                .decoder(SelectionUpdatePacket::decode)
                .consumerMainThread(SelectionUpdatePacket::handle)
                .add();
        CHANNEL.messageBuilder(RandomizerScreenPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RandomizerScreenPacket::encode)
                .decoder(RandomizerScreenPacket::decode)
                .consumerMainThread(RandomizerScreenPacket::handle)
                .add();
        CHANNEL.messageBuilder(RandomizerSavePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RandomizerSavePacket::encode)
                .decoder(RandomizerSavePacket::decode)
                .consumerMainThread(RandomizerSavePacket::handle)
                .add();
        CHANNEL.messageBuilder(GameSettingsScreenPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(GameSettingsScreenPacket::encode)
                .decoder(GameSettingsScreenPacket::decode)
                .consumerMainThread(GameSettingsScreenPacket::handle)
                .add();
        CHANNEL.messageBuilder(GameSettingsSavePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(GameSettingsSavePacket::encode)
                .decoder(GameSettingsSavePacket::decode)
                .consumerMainThread(GameSettingsSavePacket::handle)
                .add();
        CHANNEL.messageBuilder(RefillNowPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RefillNowPacket::encode)
                .decoder(RefillNowPacket::decode)
                .consumerMainThread(RefillNowPacket::handle)
                .add();
        CHANNEL.messageBuilder(MapSelectPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MapSelectPacket::encode)
                .decoder(MapSelectPacket::decode)
                .consumerMainThread(MapSelectPacket::handle)
                .add();
        CHANNEL.messageBuilder(SelectMapPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SelectMapPacket::encode)
                .decoder(SelectMapPacket::decode)
                .consumerMainThread(SelectMapPacket::handle)
                .add();
    }

    public static void sendMapSelect(ServerPlayer player, MapSelectPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendGameSettingsScreen(ServerPlayer player, GameSettingsScreenPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendRandomizerScreen(ServerPlayer player, RandomizerScreenPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
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
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PlayReplayPacket(data, skinsFor(player.getServer(), data)));
    }

    /** Live skins of online players, and the skins saved at registration for everyone else in the replay. */
    private static Map<UUID, GameSettings.Skin> skinsFor(MinecraftServer server, ReplayData data) {
        Map<UUID, GameSettings.Skin> skins = new HashMap<>();
        for (ReplayData.Actor actor : data.actors) {
            if (!actor.isPlayer()) continue;
            ServerPlayer online = server.getPlayerList().getPlayer(actor.id);
            GameSettings.Skin skin = online != null ? GameSettings.skinOf(online.getGameProfile()) : GameSettings.get().savedSkin(actor.id);
            if (skin != null) skins.put(actor.id, skin);
        }
        return skins;
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
