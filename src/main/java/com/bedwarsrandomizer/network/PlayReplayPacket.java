package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.client.ClientReplayHandler;
import com.bedwarsrandomizer.replay.ReplayData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record PlayReplayPacket(ReplayData data) {

    public void encode(FriendlyByteBuf buf) {
        data.write(buf);
    }

    public static PlayReplayPacket decode(FriendlyByteBuf buf) {
        return new PlayReplayPacket(ReplayData.read(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientReplayHandler.play(data));
    }
}
