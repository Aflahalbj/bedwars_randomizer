package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.client.GameSettingsScreen;
import com.bedwarsrandomizer.drop.RandomizerSource;
import com.bedwarsrandomizer.game.BedwarsGame;
import com.bedwarsrandomizer.game.GameSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Server → client: what {@code /bwr setting} shows. */
public record GameSettingsScreenPacket(int startCountdown, int respawnSeconds, int spawnDistance, int playersPerTeam, int[] refillSeconds,
                                       List<PlayerRow> players, List<String> unregisteredOnline, boolean gameRunning) {
    private static final int MAX_PLAYERS = 512;

    /** {@code id} null = added by name in the screen, registered on save if online. */
    public record PlayerRow(@Nullable UUID id, String name, @Nullable DyeColor team, boolean host, boolean online) {
        void write(FriendlyByteBuf buf) {
            buf.writeNullable(id, FriendlyByteBuf::writeUUID);
            buf.writeUtf(name, 64);
            buf.writeNullable(team, FriendlyByteBuf::writeEnum);
            buf.writeBoolean(host);
            buf.writeBoolean(online);
        }

        static PlayerRow read(FriendlyByteBuf buf) {
            return new PlayerRow(buf.readNullable(FriendlyByteBuf::readUUID), buf.readUtf(64), buf.readNullable(b -> b.readEnum(DyeColor.class)),
                    buf.readBoolean(), buf.readBoolean());
        }
    }

    public static GameSettingsScreenPacket create(MinecraftServer server) {
        GameSettings settings = GameSettings.get();
        int[] refill = new int[RandomizerSource.values().length];
        for (RandomizerSource source : RandomizerSource.values()) refill[source.ordinal()] = settings.refillSeconds(source);
        List<PlayerRow> players = new ArrayList<>();
        for (GameSettings.Registered player : settings.players()) {
            players.add(new PlayerRow(player.id(), player.name(), player.team(), player.host(), server.getPlayerList().getPlayer(player.id()) != null));
        }
        List<String> unregistered = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (settings.player(player.getUUID()) == null) unregistered.add(player.getGameProfile().getName());
        }
        return new GameSettingsScreenPacket(settings.startCountdown, settings.respawnSeconds, settings.spawnDistance, settings.playersPerTeam,
                refill, players, unregistered, BedwarsGame.get().isActive());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(startCountdown);
        buf.writeVarInt(respawnSeconds);
        buf.writeVarInt(spawnDistance);
        buf.writeVarInt(playersPerTeam);
        for (int seconds : refillSeconds) buf.writeVarInt(seconds);
        buf.writeVarInt(players.size());
        players.forEach(row -> row.write(buf));
        buf.writeVarInt(unregisteredOnline.size());
        unregisteredOnline.forEach(name -> buf.writeUtf(name, 64));
        buf.writeBoolean(gameRunning);
    }

    public static GameSettingsScreenPacket decode(FriendlyByteBuf buf) {
        int countdown = buf.readVarInt(), respawn = buf.readVarInt(), distance = buf.readVarInt(), perTeam = buf.readVarInt();
        int[] refill = new int[RandomizerSource.values().length];
        for (int i = 0; i < refill.length; i++) refill[i] = buf.readVarInt();
        int count = Math.min(buf.readVarInt(), MAX_PLAYERS);
        List<PlayerRow> players = new ArrayList<>(count);
        for (int i = 0; i < count; i++) players.add(PlayerRow.read(buf));
        int unregisteredCount = Math.min(buf.readVarInt(), MAX_PLAYERS);
        List<String> unregistered = new ArrayList<>(unregisteredCount);
        for (int i = 0; i < unregisteredCount; i++) unregistered.add(buf.readUtf(64));
        return new GameSettingsScreenPacket(countdown, respawn, distance, perTeam, refill, players, unregistered, buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> GameSettingsScreen.open(this));
    }
}
