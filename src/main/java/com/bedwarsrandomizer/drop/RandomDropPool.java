package com.bedwarsrandomizer.drop;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.BwrConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weighted pool of everything a random-drop block can give.
 *
 * <p>Rules:
 * <ul>
 *   <li>Only blocks, weapons, tools and armor.</li>
 *   <li>Unbreakable/technical blocks (bedrock, barrier, command blocks, ...) and the blacklist tag are excluded.</li>
 *   <li>Tools, weapons and armor stronger than iron are excluded.</li>
 *   <li>The stronger an item is, the lower its weight: blocks by hardness/blast resistance,
 *       gear by tier/armor value. Item rarity and the {@code random_drop_rare} tag lower it further.</li>
 * </ul>
 */
public final class RandomDropPool {
    public enum Category { BLOCK, WEAPON, TOOL, ARMOR }

    /** Each point of "power" multiplies the weight of gear by this factor. */
    private static final double GEAR_DECAY = 0.6;
    private static final double BASE_WEIGHT = 100.0;

    private record Entry(Item item, double cumulativeWeight, int minCount, int maxCount) {}

    private static RandomDropPool cached;
    private static FeatureFlagSet cachedFlags;

    private final Map<Category, List<Entry>> entries = new EnumMap<>(Category.class);
    private final Map<Category, Double> totals = new EnumMap<>(Category.class);
    private final Map<Item, Category> categories = new HashMap<>();

    public static synchronized RandomDropPool get(ServerLevel level) {
        FeatureFlagSet flags = level.enabledFeatures();
        if (cached == null || !flags.equals(cachedFlags)) {
            cached = new RandomDropPool(flags);
            cachedFlags = flags;
        }
        return cached;
    }

    /** Called when tags reload so blacklist edits in datapacks apply without a restart. */
    public static synchronized void invalidate() {
        cached = null;
    }

    private RandomDropPool(FeatureFlagSet flags) {
        for (Category category : Category.values()) {
            entries.put(category, new ArrayList<>());
            totals.put(category, 0.0);
        }
        for (Item item : ForgeRegistries.ITEMS) {
            classify(item, flags);
        }
        BedwarsRandomizer.LOGGER.info("Random drop pool built: {} blocks, {} weapons, {} tools, {} armor",
                entries.get(Category.BLOCK).size(), entries.get(Category.WEAPON).size(),
                entries.get(Category.TOOL).size(), entries.get(Category.ARMOR).size());
    }

    public ItemStack roll(RandomSource random) {
        Category category = rollCategory(random);
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

        ItemStack stack = entry.item().getDefaultInstance();
        stack.setCount(Mth.nextInt(random, entry.minCount(), entry.maxCount()));
        return stack;
    }

    private Category rollCategory(RandomSource random) {
        EnumMap<Category, Integer> weights = new EnumMap<>(Category.class);
        weights.put(Category.BLOCK, BwrConfig.BLOCK_CATEGORY_WEIGHT.get());
        weights.put(Category.WEAPON, BwrConfig.WEAPON_CATEGORY_WEIGHT.get());
        weights.put(Category.TOOL, BwrConfig.TOOL_CATEGORY_WEIGHT.get());
        weights.put(Category.ARMOR, BwrConfig.ARMOR_CATEGORY_WEIGHT.get());

        int total = 0;
        for (Category c : Category.values()) {
            if (entries.get(c).isEmpty()) weights.put(c, 0);
            total += weights.get(c);
        }
        if (total <= 0) {
            return null;
        }
        int pick = random.nextInt(total);
        for (Category c : Category.values()) {
            pick -= weights.get(c);
            if (pick < 0) return c;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Classification

    @SuppressWarnings("deprecation")
    private void classify(Item item, FeatureFlagSet flags) {
        if (item == Items.AIR || !item.isEnabled(flags)) return;
        if (item instanceof SpawnEggItem || item instanceof GameMasterBlockItem) return;

        ItemStack stack = item.getDefaultInstance();
        if (stack.is(ModTags.RANDOM_DROP_BLACKLIST)) return;

        double multiplier = rarityMultiplier(stack.getRarity());
        if (stack.is(ModTags.RANDOM_DROP_RARE)) multiplier *= 0.25;

        if (item instanceof ArmorItem armor) {
            ArmorMaterial material = armor.getMaterial();
            if (armor.getDefense() > ArmorMaterials.IRON.getDefenseForType(armor.getType())
                    || armor.getToughness() > ArmorMaterials.IRON.getToughness()
                    || material.getKnockbackResistance() > ArmorMaterials.IRON.getKnockbackResistance()) {
                return;
            }
            int total = 0;
            for (ArmorItem.Type type : ArmorItem.Type.values()) {
                total += material.getDefenseForType(type);
            }
            // leather = 0, gold = 2, chainmail = 2.5, iron = 4
            double power = Math.max(0, (total - 7) / 2.0);
            addGear(Category.ARMOR, item, power, multiplier);
        } else if (item instanceof ShieldItem) {
            addGear(Category.ARMOR, item, 2, multiplier);
        } else if (item instanceof ElytraItem) {
            addGear(Category.ARMOR, item, 5, multiplier);
        } else if (item instanceof TridentItem) {
            // melee damage is above an iron sword
        } else if (item instanceof TieredItem tiered) {
            Tier tier = tiered.getTier();
            if (tier.getLevel() > Tiers.IRON.getLevel()
                    || tier.getAttackDamageBonus() > Tiers.IRON.getAttackDamageBonus()) {
                return;
            }
            // wood/gold = 0, stone = 2, iron = 4
            double power = tier.getLevel() + tier.getAttackDamageBonus();
            addGear(item instanceof SwordItem ? Category.WEAPON : Category.TOOL, item, power, multiplier);
        } else if (item instanceof CrossbowItem) {
            addGear(Category.WEAPON, item, 2.5, multiplier);
        } else if (item instanceof ProjectileWeaponItem) {
            addGear(Category.WEAPON, item, 1.5, multiplier);
        } else if (item instanceof ArrowItem) {
            double power = item == Items.ARROW ? 0 : 1.5;
            add(Category.WEAPON, item, BASE_WEIGHT * Math.pow(GEAR_DECAY, power) * multiplier, 4, 12);
        } else if (item instanceof ShearsItem) {
            addGear(Category.TOOL, item, 0.5, multiplier);
        } else if (item instanceof FlintAndSteelItem || item instanceof FishingRodItem) {
            addGear(Category.TOOL, item, 1, multiplier);
        } else if (item instanceof BlockItem blockItem) {
            classifyBlock(blockItem, multiplier);
        }
    }

    private void classifyBlock(BlockItem item, double multiplier) {
        // Seeds, redstone dust, string... are block items but players see them as items.
        if (item instanceof ItemNameBlockItem || item instanceof SolidBucketItem) return;

        Block block = item.getBlock();
        if (block.defaultBlockState().is(ModTags.RANDOM_DROP_BLOCKS)) return;

        float hardness = block.defaultDestroyTime();
        if (hardness < 0) return; // unbreakable: bedrock, barrier, end portal frame, ...

        float strength = Math.max(hardness, block.getExplosionResistance());
        // glass ~72, wool ~53, stone ~26, end stone ~23, obsidian ~9
        double weight = BASE_WEIGHT / (1.0 + 1.5 * Math.log1p(strength)) * multiplier;

        int maxStack = item.getMaxStackSize();
        int max = Mth.clamp((int) Math.round(weight * 0.3), 1, Math.min(32, maxStack));
        int min = Math.max(1, max / 3);
        add(Category.BLOCK, item, weight, min, max);
    }

    private void addGear(Category category, Item item, double power, double multiplier) {
        add(category, item, BASE_WEIGHT * Math.pow(GEAR_DECAY, power) * multiplier, 1, 1);
    }

    private void add(Category category, Item item, double weight, int min, int max) {
        if (weight <= 0) return;
        double total = totals.get(category) + weight;
        totals.put(category, total);
        entries.get(category).add(new Entry(item, total, min, max));
        categories.put(item, category);
    }

    @Nullable
    public Category categoryOf(Item item) {
        return categories.get(item);
    }

    private static double rarityMultiplier(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> 1.0;
            case UNCOMMON -> 0.5;
            case RARE -> 0.25;
            case EPIC -> 0.1;
        };
    }
}
