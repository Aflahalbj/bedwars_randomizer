package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.client.ClientReplayHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record StopReplayPacket() {

    public void encode(FriendlyByteBuf buf) {
    }

    public static StopReplayPacket decode(FriendlyByteBuf buf) {
        return new StopReplayPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientReplayHandler::stop);
    }
}
