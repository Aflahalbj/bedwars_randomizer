package com.bedwarsrandomizer.network;

import com.bedwarsrandomizer.client.RandomizerScreen;
import com.bedwarsrandomizer.drop.DropCandidates;
import com.bedwarsrandomizer.drop.DropCategory;
import com.bedwarsrandomizer.drop.RandomizerSettings;
import com.bedwarsrandomizer.drop.RandomizerSettings.Rule;
import com.bedwarsrandomizer.drop.RandomizerSource;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server → client: everything the randomizer settings screen shows, for every randomizer block. */
public record RandomizerScreenPacket(List<Row> rows, List<SourceData> sources) {
    public record Row(String id, ItemStack stack, DropCategory category) {}

    /** {@code defaults} and {@code current} line up with {@code rows}. */
    public record SourceData(boolean enabled, int[] categoryWeights, int[] defaultCategoryWeights, Rule[] defaults, Rule[] current) {}

    private static final int CATEGORY_COUNT = DropCategory.values().length;

    public static RandomizerScreenPacket create(ServerLevel level) {
        List<DropCandidates.Candidate> candidates = DropCandidates.get(level.enabledFeatures());
        List<Row> rows = new ArrayList<>(candidates.size());
        for (DropCandidates.Candidate candidate : candidates) {
            rows.add(new Row(candidate.id(), candidate.stack(), candidate.category()));
        }
        List<SourceData> sources = new ArrayList<>();
        RandomizerSettings settings = RandomizerSettings.get();
        for (RandomizerSource source : RandomizerSource.values()) {
            RandomizerSettings.SourceSettings sourceSettings = settings.source(source);
            int[] weights = new int[CATEGORY_COUNT];
            int[] defaultWeights = new int[CATEGORY_COUNT];
            for (DropCategory category : DropCategory.values()) {
                weights[category.ordinal()] = sourceSettings.categoryWeight(category);
                defaultWeights[category.ordinal()] = RandomizerSettings.defaultCategoryWeight(source, category);
            }
            Rule[] defaults = new Rule[candidates.size()];
            Rule[] current = new Rule[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                defaults[i] = candidates.get(i).defaults(source);
                current[i] = sourceSettings.ruleFor(source, candidates.get(i));
            }
            sources.add(new SourceData(sourceSettings.enabled(), weights, defaultWeights, defaults, current));
        }
        return new RandomizerScreenPacket(rows, sources);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(rows.size());
        for (Row row : rows) {
            buf.writeUtf(row.id(), 256);
            buf.writeItem(row.stack());
            buf.writeEnum(row.category());
        }
        for (SourceData source : sources) {
            buf.writeBoolean(source.enabled());
            for (int i = 0; i < CATEGORY_COUNT; i++) {
                buf.writeVarInt(source.categoryWeights()[i]);
                buf.writeVarInt(source.defaultCategoryWeights()[i]);
            }
            for (int i = 0; i < rows.size(); i++) {
                source.defaults()[i].write(buf);
                source.current()[i].write(buf);
            }
        }
    }

    public static RandomizerScreenPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Row> rows = new ArrayList<>(Math.min(count, 8192));
        for (int i = 0; i < count; i++) {
            rows.add(new Row(buf.readUtf(256), buf.readItem(), buf.readEnum(DropCategory.class)));
        }
        List<SourceData> sources = new ArrayList<>();
        for (int s = 0; s < RandomizerSource.values().length; s++) {
            boolean enabled = buf.readBoolean();
            int[] weights = new int[CATEGORY_COUNT];
            int[] defaultWeights = new int[CATEGORY_COUNT];
            for (int i = 0; i < CATEGORY_COUNT; i++) {
                weights[i] = buf.readVarInt();
                defaultWeights[i] = buf.readVarInt();
            }
            Rule[] defaults = new Rule[count];
            Rule[] current = new Rule[count];
            for (int i = 0; i < count; i++) {
                defaults[i] = Rule.read(buf);
                current[i] = Rule.read(buf);
            }
            sources.add(new SourceData(enabled, weights, defaultWeights, defaults, current));
        }
        return new RandomizerScreenPacket(rows, sources);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> RandomizerScreen.open(this));
    }
}
