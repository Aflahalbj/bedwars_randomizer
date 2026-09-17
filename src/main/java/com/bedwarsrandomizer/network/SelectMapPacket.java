package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.arena.Arena;
import com.bedwarsrandomizer.game.BedwarsGame;
import com.bedwarsrandomizer.game.GameSettings;
import com.bedwarsrandomizer.network.MapSelectPacket.Then;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server: the chosen map; paste it if needed, then do what the map screen was opened for. */
public record SelectMapPacket(String map, Then then) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(map, 128);
        buf.writeEnum(then);
    }

    public static SelectMapPacket decode(FriendlyByteBuf buf) {
        return new SelectMapPacket(buf.readUtf(128), buf.readEnum(Then.class));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender == null || !(sender.hasPermissions(2) || GameSettings.get().isHost(sender.getUUID()))) return;
        MinecraftServer server = sender.getServer();
        if (BedwarsGame.get().isActive()) {
            sender.sendSystemMessage(Component.literal("[BWR] A round is running; the map can't change now.").withStyle(ChatFormatting.RED));
            return;
        }
        if (!Arena.selectMap(server, map)) {
            sender.sendSystemMessage(Component.literal("[BWR] Unknown map: " + map).withStyle(ChatFormatting.RED));
            return;
        }
        switch (then) {
            case TELEPORT_ALL -> {
                int count = BedwarsGame.teleportAllToLobby(server);
                sender.sendSystemMessage(Component.literal("[BWR] Map " + map + ": teleported " + count + " registered player(s) to the lobby.")
                        .withStyle(ChatFormatting.GREEN));
            }
            case GO_TO_LOBBY -> {
                if (Arena.teleportToLobby(sender)) {
                    sender.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
                    sender.sendSystemMessage(Component.literal("[BWR] Map " + map + ". Welcome to the lobby!").withStyle(ChatFormatting.GREEN));
                }
            }
            case START -> {
                Component error = BedwarsGame.get().start(server, sender);
                sender.sendSystemMessage(error != null ? error.copy().withStyle(ChatFormatting.RED)
                        : Component.literal("[BWR] Map " + map + ". Bed Wars is starting!").withStyle(ChatFormatting.GREEN));
            }
            case NOTHING -> sender.sendSystemMessage(Component.literal("[BWR] The arena map is now " + map + ".").withStyle(ChatFormatting.GREEN));
        }
    }
}
