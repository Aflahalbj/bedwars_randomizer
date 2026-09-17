package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.arena.Arena;
import com.bedwarsrandomizer.game.BedwarsGame;
import com.bedwarsrandomizer.game.GameSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server: the chosen map; paste it if needed and, from [Teleport all to lobby], bring everyone there. */
public record SelectMapPacket(String map, boolean teleportAll) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(map, 128);
        buf.writeBoolean(teleportAll);
    }

    public static SelectMapPacket decode(FriendlyByteBuf buf) {
        return new SelectMapPacket(buf.readUtf(128), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender == null || !(sender.hasPermissions(2) || GameSettings.get().isHost(sender.getUUID()))) return;
        if (BedwarsGame.get().isActive()) {
            sender.sendSystemMessage(Component.literal("[BWR] A round is running; the map can't change now.").withStyle(ChatFormatting.RED));
            return;
        }
        if (!Arena.selectMap(sender.getServer(), map)) {
            sender.sendSystemMessage(Component.literal("[BWR] Unknown map: " + map).withStyle(ChatFormatting.RED));
            return;
        }
        if (teleportAll) {
            int count = BedwarsGame.teleportAllToLobby(sender.getServer());
            sender.sendSystemMessage(Component.literal("[BWR] Map " + map + ": teleported " + count + " registered player(s) to the lobby.")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            sender.sendSystemMessage(Component.literal("[BWR] The arena map is now " + map + ".").withStyle(ChatFormatting.GREEN));
        }
    }
}
