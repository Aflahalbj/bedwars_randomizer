package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.arena.Arena;
import com.bedwarsrandomizer.drop.ModTags;
import com.bedwarsrandomizer.drop.RandomizerSource;
import com.bedwarsrandomizer.game.BedwarsGame;
import com.bedwarsrandomizer.game.GameSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server: the settings screen's "Refill now" buttons. */
public record RefillNowPacket(RandomizerSource source) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(source);
    }

    public static RefillNowPacket decode(FriendlyByteBuf buf) {
        return new RefillNowPacket(buf.readEnum(RandomizerSource.class));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender == null || !(sender.hasPermissions(2) || GameSettings.get().isHost(sender.getUUID()))) return;
        BedwarsGame game = BedwarsGame.get();
        int placed = game.isActive()
                ? game.refillNow(sender.getServer(), source)
                // no game: put the blocks back from the map itself
                : Arena.restoreBlocks(sender.getServer(), state -> state.is(ModTags.RANDOM_DROP_BLOCKS) && RandomizerSource.of(state) == source);
        sender.sendSystemMessage(Component.literal("[BWR] Refilled " + source.key().replace('_', ' ') + " (" + placed + " block(s) placed).")
                .withStyle(ChatFormatting.GREEN));
    }
}
