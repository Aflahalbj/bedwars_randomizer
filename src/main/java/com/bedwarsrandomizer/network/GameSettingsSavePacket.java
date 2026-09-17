package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.drop.RandomizerSource;
import com.bedwarsrandomizer.game.BedwarsGame;
import com.bedwarsrandomizer.game.GameSettings;
import com.bedwarsrandomizer.network.GameSettingsScreenPacket.PlayerRow;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Client → server: the saved {@code /bwr setting} screen. The player list replaces the registered players. */
public record GameSettingsSavePacket(int startCountdown, int respawnSeconds, int spawnDistance, int playersPerTeam, int[] refillSeconds,
                                     List<PlayerRow> players) {
    private static final int MAX_PLAYERS = 512;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(startCountdown);
        buf.writeVarInt(respawnSeconds);
        buf.writeVarInt(spawnDistance);
        buf.writeVarInt(playersPerTeam);
        for (int seconds : refillSeconds) buf.writeVarInt(seconds);
        buf.writeVarInt(players.size());
        players.forEach(row -> row.write(buf));
    }

    public static GameSettingsSavePacket decode(FriendlyByteBuf buf) {
        int countdown = buf.readVarInt(), respawn = buf.readVarInt(), distance = buf.readVarInt(), perTeam = buf.readVarInt();
        int[] refill = new int[RandomizerSource.values().length];
        for (int i = 0; i < refill.length; i++) refill[i] = buf.readVarInt();
        int count = Math.min(buf.readVarInt(), MAX_PLAYERS);
        List<PlayerRow> players = new ArrayList<>(count);
        for (int i = 0; i < count; i++) players.add(PlayerRow.read(buf));
        return new GameSettingsSavePacket(countdown, respawn, distance, perTeam, refill, players);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender == null || !sender.hasPermissions(2)) return;
        GameSettings settings = GameSettings.get();
        settings.setNumbers(startCountdown, respawnSeconds, spawnDistance, playersPerTeam);
        for (RandomizerSource source : RandomizerSource.values()) settings.setRefillSeconds(source, refillSeconds[source.ordinal()]);

        List<GameSettings.Registered> registered = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        boolean newPlayers = false;
        for (PlayerRow row : players) {
            if (row.id() != null) {
                GameSettings.Registered old = settings.player(row.id());
                registered.add(new GameSettings.Registered(row.id(), row.name(), row.team(), row.host(), old == null ? null : old.skin()));
                continue;
            }
            ServerPlayer player = sender.getServer().getPlayerList().getPlayerByName(row.name());
            if (player == null) {
                notFound.add(row.name());
                continue;
            }
            if (registered.stream().noneMatch(r -> r.id().equals(player.getUUID()))) {
                registered.add(new GameSettings.Registered(player.getUUID(), player.getGameProfile().getName(), row.team(), row.host(),
                        GameSettings.skinOf(player.getGameProfile())));
                if (settings.player(player.getUUID()) == null) {
                    BedwarsGame.notifyRegistered(player);
                    newPlayers = true;
                }
            }
        }
        settings.setPlayers(registered);
        settings.save();
        if (newPlayers) BedwarsGame.sendLobbyLink(sender.getServer(), sender);

        sender.sendSystemMessage(Component.literal("[BWR] Game settings saved (" + registered.size() + " registered player(s)).")
                .withStyle(ChatFormatting.GREEN));
        if (!notFound.isEmpty()) {
            sender.sendSystemMessage(Component.literal("[BWR] Not online, so not registered: " + String.join(", ", notFound)).withStyle(ChatFormatting.YELLOW));
        }
        if (BedwarsGame.get().isActive()) {
            sender.sendSystemMessage(Component.literal("[BWR] A game is running: teams and players change for the next game.").withStyle(ChatFormatting.GRAY));
        }
    }
}
