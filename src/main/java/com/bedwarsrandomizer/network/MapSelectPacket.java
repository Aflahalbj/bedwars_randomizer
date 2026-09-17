package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.client.MapSelectScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → client: open the map choice. From [Teleport all to lobby] ({@code teleportAll}) the choice also brings every
 * registered player to the lobby; from {@code /bwr maps} it only changes the map.
 */
public record MapSelectPacket(List<String> maps, String current, boolean teleportAll) {
    private static final int MAX_MAPS = 256;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(maps.size());
        maps.forEach(map -> buf.writeUtf(map, 128));
        buf.writeUtf(current, 128);
        buf.writeBoolean(teleportAll);
    }

    public static MapSelectPacket decode(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_MAPS);
        List<String> maps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) maps.add(buf.readUtf(128));
        return new MapSelectPacket(maps, buf.readUtf(128), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MapSelectScreen.open(this));
    }
}
