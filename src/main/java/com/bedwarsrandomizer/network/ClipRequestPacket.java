package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.client.ClientClipRecorder;
import com.bedwarsrandomizer.replay.KillCause;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Server → client: "a kill just happened near you, send me what you saw". */
public record ClipRequestPacket(UUID replayId, UUID killerId, String killerName, UUID victimId, String victimName,
                                KillCause cause, ItemStack weapon, int preFrames, int postFrames) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(replayId);
        buf.writeUUID(killerId);
        buf.writeUtf(killerName);
        buf.writeUUID(victimId);
        buf.writeUtf(victimName);
        buf.writeEnum(cause);
        buf.writeItem(weapon);
        buf.writeVarInt(preFrames);
        buf.writeVarInt(postFrames);
    }

    public static ClipRequestPacket decode(FriendlyByteBuf buf) {
        return new ClipRequestPacket(buf.readUUID(), buf.readUUID(), buf.readUtf(), buf.readUUID(), buf.readUtf(),
                buf.readEnum(KillCause.class), buf.readItem(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientClipRecorder.request(this));
    }
}
