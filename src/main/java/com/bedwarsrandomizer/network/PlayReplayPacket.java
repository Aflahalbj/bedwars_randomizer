package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.client.ClientReplayHandler;
import com.bedwarsrandomizer.game.GameSettings;
import com.bedwarsrandomizer.replay.ReplayData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Server → client: a replay to play, with the saved skins of its players (offline players have no tab list skin). */
public record PlayReplayPacket(ReplayData data, Map<UUID, GameSettings.Skin> skins) {
    private static final int MAX_SKINS = 64;

    public void encode(FriendlyByteBuf buf) {
        data.write(buf);
        buf.writeVarInt(skins.size());
        skins.forEach((id, skin) -> {
            buf.writeUUID(id);
            buf.writeUtf(skin.value());
            buf.writeNullable(skin.signature(), FriendlyByteBuf::writeUtf);
        });
    }

    public static PlayReplayPacket decode(FriendlyByteBuf buf) {
        ReplayData data = ReplayData.read(buf);
        int count = Math.min(buf.readVarInt(), MAX_SKINS);
        Map<UUID, GameSettings.Skin> skins = new HashMap<>();
        for (int i = 0; i < count; i++) {
            skins.put(buf.readUUID(), new GameSettings.Skin(buf.readUtf(), buf.readNullable(FriendlyByteBuf::readUtf)));
        }
        return new PlayReplayPacket(data, skins);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientReplayHandler.play(data, skins));
    }
}
