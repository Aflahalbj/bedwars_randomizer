package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.replay.ClipAssembler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Client → server: one piece of a kill recording (serverbound payloads are limited to 32767 bytes). */
public record ClipChunkPacket(UUID replayId, int index, int total, byte[] bytes) {
    public static final int CHUNK_BYTES = 30_000;
    public static final int MAX_CHUNKS = 64;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(replayId);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(bytes);
    }

    public static ClipChunkPacket decode(FriendlyByteBuf buf) {
        return new ClipChunkPacket(buf.readUUID(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(CHUNK_BYTES));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null) {
            ClipAssembler.receive(sender.getServer(), sender, this);
        }
    }
}
