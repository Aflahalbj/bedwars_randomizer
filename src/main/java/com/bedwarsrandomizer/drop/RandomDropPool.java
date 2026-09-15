package com.bedwarsrandomizer.drop;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.drop.DropCandidates.Candidate;
import com.bedwarsrandomizer.drop.RandomizerSettings.Rule;
import com.bedwarsrandomizer.drop.RandomizerSettings.SourceSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.*;

/** The weighted drops of one randomizer block, built from {@link DropCandidates} and {@link RandomizerSettings}. */
public final class RandomDropPool {
    private record Entry(ItemStack stack, double cumulativeWeight, int minCount, int maxCount) {}

    private static final EnumMap<RandomizerSource, RandomDropPool> CACHE = new EnumMap<>(RandomizerSource.class);
    private static FeatureFlagSet cachedFlags;

    private final SourceSettings settings;
    private final EnumMap<DropCategory, List<Entry>> entries = new EnumMap<>(DropCategory.class);
    private final EnumMap<DropCategory, Double> totals = new EnumMap<>(DropCategory.class);
    private final Map<Item, DropCategory> categories = new HashMap<>();

    public static synchronized RandomDropPool get(ServerLevel level, RandomizerSource source) {
        FeatureFlagSet flags = level.enabledFeatures();
        if (!flags.equals(cachedFlags)) {
            CACHE.clear();
            cachedFlags = flags;
        }
        return CACHE.computeIfAbsent(source, s -> new RandomDropPool(s, flags));
    }

    /** Called when tags reload or settings change. */
    public static synchronized void invalidate() {
        CACHE.clear();
        DropCandidates.invalidate();
    }

    private RandomDropPool(RandomizerSource source, FeatureFlagSet flags) {
        settings = RandomizerSettings.get().source(source);
        for (DropCategory category : DropCategory.values()) {
            entries.put(category, new ArrayList<>());
            totals.put(category, 0.0);
        }
        for (Candidate candidate : DropCandidates.get(flags)) {
            Rule rule = settings.ruleFor(source, candidate);
            if (!rule.enabled() || rule.weight() <= 0) continue;
            double total = totals.get(candidate.category()) + rule.weight();
            totals.put(candidate.category(), total);
            entries.get(candidate.category()).add(new Entry(candidate.stack(), total, rule.min(), rule.max()));
            categories.putIfAbsent(candidate.item(), candidate.category());
        }
        Map<DropCategory, Integer> sizes = new EnumMap<>(DropCategory.class);
        entries.forEach((category, list) -> sizes.put(category, list.size()));
        BedwarsRandomizer.LOGGER.info("Random drop pool for {} built: {}", source.key(), sizes);
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /**
     * One random drop. A colored block (wool, concrete...) comes in {@code color} when given (the color of the broken
     * glazed terracotta), otherwise in a random color.
     */
    public ItemStack roll(RandomSource random, @Nullable DyeColor color) {
        DropCategory category = rollCategory(random);
        if (category == null) {
            return ItemStack.EMPTY;
        }
        List<Entry> list = entries.get(category);
        double target = random.nextDouble() * totals.get(category);

        int lo = 0, hi = list.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).cumulativeWeight() < target) lo = mid + 1;
            else hi = mid;
        }
        Entry entry = list.get(lo);

        ItemStack stack = entry.stack().copy();
        if (ColoredBlocks.family(stack.getItem()) != null) {
            DyeColor dropColor = color != null ? color : DyeColor.byId(random.nextInt(DyeColor.values().length));
            stack = new ItemStack(ColoredBlocks.withColor(stack.getItem(), dropColor));
        }
        stack.setCount(Math.min(Mth.nextInt(random, entry.minCount(), entry.maxCount()), stack.getMaxStackSize()));
        return stack;
    }

    @Nullable
    private DropCategory rollCategory(RandomSource random) {
        int total = 0;
        for (DropCategory category : DropCategory.values()) {
            if (!entries.get(category).isEmpty()) total += settings.categoryWeight(category);
        }
        if (total <= 0) {
            return null;
        }
        int pick = random.nextInt(total);
        for (DropCategory category : DropCategory.values()) {
            if (entries.get(category).isEmpty()) continue;
            pick -= settings.categoryWeight(category);
            if (pick < 0) return category;
        }
        return null;
    }

    @Nullable
    public DropCategory categoryOf(Item item) {
        DropCategory category = categories.get(item);
        if (category == null && ColoredBlocks.family(item) != null) {
            category = categories.get(ColoredBlocks.withColor(item, DyeColor.WHITE));
        }
        return category;
    }
}
