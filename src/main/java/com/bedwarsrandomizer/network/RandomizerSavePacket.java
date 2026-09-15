package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.drop.DropCategory;
import com.bedwarsrandomizer.drop.RandomizerSettings;
import com.bedwarsrandomizer.drop.RandomizerSettings.Rule;
import com.bedwarsrandomizer.drop.RandomizerSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.function.Supplier;

/**
 * Client → server: saved randomizer settings. Only rules that differ from the defaults are sent, split over several
 * packets (serverbound packets are limited to 32 KB); the last part applies them.
 */
public record RandomizerSavePacket(int part, boolean last, List<SourceHeader> headers, List<Change> changes) {
    public static final int CHANGES_PER_PACKET = 300;
    private static final int MAX_TOTAL_CHANGES = 30_000;
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    public record SourceHeader(boolean enabled, int[] categoryWeights) {}

    public record Change(RandomizerSource source, String id, Rule rule) {}

    private static final class Pending {
        final List<SourceHeader> headers;
        final List<Change> changes = new ArrayList<>();
        int nextPart = 1;

        Pending(List<SourceHeader> headers) {
            this.headers = headers;
        }
    }

    public static List<RandomizerSavePacket> split(List<SourceHeader> headers, List<Change> changes) {
        int parts = Math.max(1, (changes.size() + CHANGES_PER_PACKET - 1) / CHANGES_PER_PACKET);
        List<RandomizerSavePacket> packets = new ArrayList<>(parts);
        for (int p = 0; p < parts; p++) {
            List<Change> chunk = changes.subList(Math.min(changes.size(), p * CHANGES_PER_PACKET), Math.min(changes.size(), (p + 1) * CHANGES_PER_PACKET));
            packets.add(new RandomizerSavePacket(p, p == parts - 1, p == 0 ? headers : List.of(), List.copyOf(chunk)));
        }
        return packets;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(part);
        buf.writeBoolean(last);
        buf.writeVarInt(headers.size());
        for (SourceHeader header : headers) {
            buf.writeBoolean(header.enabled());
            for (int weight : header.categoryWeights()) buf.writeVarInt(weight);
        }
        buf.writeVarInt(changes.size());
        for (Change change : changes) {
            buf.writeEnum(change.source());
            buf.writeUtf(change.id(), 256);
            change.rule().write(buf);
        }
    }

    public static RandomizerSavePacket decode(FriendlyByteBuf buf) {
        int part = buf.readVarInt();
        boolean last = buf.readBoolean();
        int headerCount = Math.min(buf.readVarInt(), RandomizerSource.values().length);
        List<SourceHeader> headers = new ArrayList<>(headerCount);
        for (int h = 0; h < headerCount; h++) {
            boolean enabled = buf.readBoolean();
            int[] weights = new int[DropCategory.values().length];
            for (int i = 0; i < weights.length; i++) weights[i] = buf.readVarInt();
            headers.add(new SourceHeader(enabled, weights));
        }
        int changeCount = Math.min(buf.readVarInt(), CHANGES_PER_PACKET);
        List<Change> changes = new ArrayList<>(changeCount);
        for (int c = 0; c < changeCount; c++) {
            changes.add(new Change(buf.readEnum(RandomizerSource.class), buf.readUtf(256), Rule.read(buf)));
        }
        return new RandomizerSavePacket(part, last, headers, changes);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender == null || !sender.hasPermissions(2)) return;
        UUID id = sender.getUUID();

        Pending pending;
        if (part == 0) {
            if (headers.size() != RandomizerSource.values().length) return;
            pending = new Pending(headers);
            PENDING.put(id, pending);
        } else {
            pending = PENDING.get(id);
            if (pending == null || pending.nextPart != part) {
                PENDING.remove(id);
                return;
            }
            pending.nextPart++;
        }
        pending.changes.addAll(changes);
        if (pending.changes.size() > MAX_TOTAL_CHANGES) {
            PENDING.remove(id);
            return;
        }
        if (!last) return;
        PENDING.remove(id);

        Map<RandomizerSource, RandomizerSettings.SourceSettings> sources = new EnumMap<>(RandomizerSource.class);
        for (RandomizerSource source : RandomizerSource.values()) {
            SourceHeader header = pending.headers.get(source.ordinal());
            Map<DropCategory, Integer> weights = new EnumMap<>(DropCategory.class);
            for (DropCategory category : DropCategory.values()) weights.put(category, header.categoryWeights()[category.ordinal()]);
            Map<String, Rule> overrides = new TreeMap<>();
            for (Change change : pending.changes) {
                if (change.source() == source) overrides.put(change.id(), change.rule());
            }
            sources.put(source, RandomizerSettings.SourceSettings.of(source, header.enabled(), weights, overrides));
        }
        RandomizerSettings.replace(new RandomizerSettings(sources));
        sender.sendSystemMessage(Component.literal("[BWR] Randomizer settings saved (" + pending.changes.size() + " change(s) from default).")
                .withStyle(ChatFormatting.GREEN));
    }
}
