package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.client.MapSelectScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server → client: open the map choice; {@code then} says what happens once a map is picked. */
public record MapSelectPacket(List<String> maps, String current, Then then) {
    private static final int MAX_MAPS = 256;

    public enum Then {
        /** {@code /bwr maps}: only change the map. */
        NOTHING,
        /** [Teleport all to lobby]: every registered player goes to the lobby. */
        TELEPORT_ALL,
        /** {@code /bwr gotolobby} before any map was chosen: the chooser goes to the lobby. */
        GO_TO_LOBBY,
        /** {@code /bwr start} before any map was chosen: the round starts. */
        START
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(maps.size());
        maps.forEach(map -> buf.writeUtf(map, 128));
        buf.writeUtf(current, 128);
        buf.writeEnum(then);
    }

    public static MapSelectPacket decode(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_MAPS);
        List<String> maps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) maps.add(buf.readUtf(128));
        return new MapSelectPacket(maps, buf.readUtf(128), buf.readEnum(Then.class));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MapSelectScreen.open(this));
    }
}
