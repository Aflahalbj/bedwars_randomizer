package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.block.selection.SelectionBlockEntity;
import com.bedwarsrandomizer.block.selection.SelectionPaster;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server: new selection block settings from the screen, optionally followed by Save (paste everywhere). */
public record SelectionUpdatePacket(BlockPos pos, SelectionBlockEntity.Settings settings, boolean save) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBlockPos(settings.offset());
        buf.writeBlockPos(settings.size());
        buf.writeBoolean(settings.showBox());
        buf.writeNullable(settings.team(), FriendlyByteBuf::writeEnum);
        buf.writeBoolean(save);
    }

    public static SelectionUpdatePacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        SelectionBlockEntity.Settings settings = new SelectionBlockEntity.Settings(buf.readBlockPos(), buf.readBlockPos(),
                buf.readBoolean(), buf.readNullable(b -> b.readEnum(DyeColor.class)));
        return new SelectionUpdatePacket(pos, settings, buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender == null || !sender.canUseGameMasterBlocks()) return;
        ServerLevel level = sender.serverLevel();
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof SelectionBlockEntity selection)) return;
        selection.setSettings(settings);
        if (save) {
            SelectionPaster.saveAndPaste(sender, selection);
        }
    }
}
