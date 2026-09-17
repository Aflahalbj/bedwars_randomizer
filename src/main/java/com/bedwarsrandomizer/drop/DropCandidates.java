package com.bedwarsrandomizer.drop;

import com.bedwarsrandomizer.drop.RandomizerSettings.Rule;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Everything a randomizer block could drop: every item in the game, plus every splash potion and every enchanted
 * book level. Each has a default rule per randomizer; most are off. Randomizer blocks themselves are never listed.
 *
 * <p>Defaults:
 * <ul>
 *   <li>Glazed terracotta: mostly wool (4-10 at once), other full blocks, gear up to iron, arrows, snowballs, fire
 *       charges. Stronger = rarer and fewer (never more than 5, except wool).</li>
 *   <li>Warped hyphae: food up to golden apple, splash potions without level II, enchantments up to level II
 *       (no treasure or curses). Stronger = rarer.</li>
 *   <li>Note block: totems, ender pearls, obsidian, TNT, gear up to diamond, all splash potions and enchantments; the
 *       stronger, the more common. Junk blocks stay possible but rare.</li>
 * </ul>
 */
public final class DropCandidates {
    public record Candidate(String id, ItemStack stack, DropCategory category, EnumMap<RandomizerSource, Rule> defaults) {
        public Item item() {
            return stack.getItem();
        }

        public Rule defaults(RandomizerSource source) {
            return defaults.get(source);
        }
    }

    public static final int MAX_DEFAULT_COUNT = 5;
    private static final double BASE_WEIGHT = 100.0;
    private static final double GLAZED_GEAR_DECAY = 0.6;
    private static final double NOTE_BLOCK_GEAR_GROWTH = 1.35;
    private static final double IRON_POWER = 4.0;
    private static final double DIAMOND_POWER = 6.5;
    /** Effects that win fights; potions with them are rarer (hyphae) or more common (note block). */
    private static final Set<MobEffect> STRONG_EFFECTS = Set.of(MobEffects.DAMAGE_BOOST, MobEffects.REGENERATION, MobEffects.HEAL,
            MobEffects.INVISIBILITY, MobEffects.DAMAGE_RESISTANCE, MobEffects.MOVEMENT_SPEED, MobEffects.FIRE_RESISTANCE);

    private static List<Candidate> cached;
    private static FeatureFlagSet cachedFlags;

    public static synchronized List<Candidate> get(FeatureFlagSet flags) {
        if (cached == null || !flags.equals(cachedFlags)) {
            cached = build(flags);
            cachedFlags = flags;
        }
        return cached;
    }

    public static synchronized void invalidate() {
        cached = null;
    }

    private static List<Candidate> build(FeatureFlagSet flags) {
        List<Candidate> list = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            if (item == Items.AIR || !item.isEnabled(flags) || isRandomizerBlock(item)) continue;
            ItemStack stack = new ItemStack(item);
            DropCategory category = categoryOf(item);
            boolean excluded = item instanceof SpawnEggItem || item instanceof GameMasterBlockItem || stack.is(ModTags.RANDOM_DROP_BLACKLIST);
            EnumMap<RandomizerSource, Rule> defaults = new EnumMap<>(RandomizerSource.class);
            for (RandomizerSource source : RandomizerSource.values()) {
                defaults.put(source, excluded ? off(10, 1, 1) : itemRule(source, item, stack, category));
            }
            list.add(new Candidate(ForgeRegistries.ITEMS.getKey(item).toString(), stack, category, defaults));
        }

        for (Potion potion : ForgeRegistries.POTIONS) {
            if (potion.getEffects().isEmpty()) continue;
            ItemStack stack = PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), potion);
            EnumMap<RandomizerSource, Rule> defaults = new EnumMap<>(RandomizerSource.class);
            for (RandomizerSource source : RandomizerSource.values()) defaults.put(source, potionRule(source, potion));
            list.add(new Candidate("minecraft:splash_potion#" + ForgeRegistries.POTIONS.getKey(potion), stack, DropCategory.POTION, defaults));
        }

        for (Enchantment enchantment : ForgeRegistries.ENCHANTMENTS) {
            for (int level = enchantment.getMinLevel(); level <= enchantment.getMaxLevel(); level++) {
                ItemStack stack = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(enchantment, level));
                EnumMap<RandomizerSource, Rule> defaults = new EnumMap<>(RandomizerSource.class);
                for (RandomizerSource source : RandomizerSource.values()) defaults.put(source, enchantmentRule(source, enchantment, level));
                list.add(new Candidate("minecraft:enchanted_book#" + ForgeRegistries.ENCHANTMENTS.getKey(enchantment) + "/" + level,
                        stack, DropCategory.ENCHANTMENT, defaults));
            }
        }

        list.sort(Comparator.comparing(Candidate::category).thenComparing(Candidate::id));
        return List.copyOf(list);
    }

    private static boolean isRandomizerBlock(Item item) {
        return item instanceof BlockItem blockItem && blockItem.getBlock().defaultBlockState().is(ModTags.RANDOM_DROP_BLOCKS);
    }

    public static DropCategory categoryOf(Item item) {
        if (item instanceof ArmorItem || item instanceof ShieldItem || item instanceof ElytraItem) return DropCategory.ARMOR;
        if (item instanceof SwordItem || item instanceof TridentItem || item instanceof ProjectileWeaponItem) return DropCategory.WEAPON;
        if (item instanceof TieredItem || item instanceof ShearsItem || item instanceof FishingRodItem || item instanceof FlintAndSteelItem) {
            return DropCategory.TOOL;
        }
        if (item instanceof PotionItem) return DropCategory.POTION;
        if (item instanceof EnchantedBookItem) return DropCategory.ENCHANTMENT;
        if (item.isEdible()) return DropCategory.FOOD;
        if (item instanceof BlockItem) return DropCategory.BLOCK;
        return DropCategory.ITEM;
    }

    // ------------------------------------------------------------------------------------------
    // Default rules

    private static Rule itemRule(RandomizerSource source, Item item, ItemStack stack, DropCategory category) {
        Rule special = specialItem(source, item);
        if (special != null) return special;
        return switch (category) {
            case BLOCK -> blockRule(source, item, stack);
            case WEAPON, TOOL, ARMOR -> gearRule(source, item, stack);
            case FOOD -> foodRule(source, item);
            default -> off(10, 1, 1);
        };
    }

    @Nullable
    private static Rule specialItem(RandomizerSource source, Item item) {
        boolean glazed = source == RandomizerSource.GLAZED_TERRACOTTA;
        boolean note = source == RandomizerSource.NOTE_BLOCK;
        // wool (in the glazed terracotta's color) is the most common drop, plenty at once: bridges and bed defense
        if (glazed && item == Items.WHITE_WOOL) return new Rule(true, 1000, 4, 10);
        if (item == Items.ELYTRA) return off(1, 1, 1);
        if (item == Items.ARROW) return new Rule(glazed || note, glazed ? 40 : 10, 2, 5);
        if (item == Items.SNOWBALL) return new Rule(glazed || note, glazed ? 40 : 5, 2, 5);
        if (item == Items.FIRE_CHARGE) return new Rule(glazed || note, glazed ? 15 : 30, 1, glazed ? 2 : 3);
        // only from note blocks
        if (item == Items.ENDER_PEARL) return new Rule(note, note ? 40 : 5, 1, note ? 2 : 1);
        if (item == Items.TOTEM_OF_UNDYING) return new Rule(note, note ? 15 : 1, 1, 1);
        // clears every effect (poison, weakness...), so it's a strong item too
        if (item == Items.MILK_BUCKET) return new Rule(note, 15, 1, 1);
        if (item == Items.OBSIDIAN || item == Items.TNT) return new Rule(note, note ? 150 : 9, 1, 2);
        // TNT lights itself when placed during a game, so no flint and steel
        if (item == Items.FLINT_AND_STEEL) return off(12, 1, 1);
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Rule blockRule(RandomizerSource source, Item item, ItemStack stack) {
        if (!(item instanceof BlockItem blockItem) || item instanceof ItemNameBlockItem || item instanceof SolidBucketItem) return off(10, 1, 1);
        Block block = blockItem.getBlock();
        float hardness = block.defaultDestroyTime();
        if (hardness < 0) return off(1, 1, 1); // unbreakable: bedrock, barrier, ...

        float strength = Math.max(hardness, block.getExplosionResistance());
        // glass ~72, wool ~53, stone ~26, end stone ~23, obsidian ~9
        double weight = BASE_WEIGHT / (1.0 + 1.5 * Math.log1p(strength)) * rarityMultiplier(stack);
        // stronger blocks drop fewer at once: glass 5, wool 4, stone 2, obsidian 1
        int max = Mth.clamp((int) Math.round(weight / 15), 1, Math.min(MAX_DEFAULT_COUNT, item.getMaxStackSize()));
        int min = (max + 1) / 2;
        // quartered so wool (weight 1000) clearly stays the most common block
        int intWeight = Math.max(1, (int) Math.round(weight / 4));
        // a colored block drops in the glazed terracotta's color (or a random one), so one entry per color family
        boolean on = isFullBlock(block) && ColoredBlocks.isUncoloredOrWhite(item);
        return switch (source) {
            case GLAZED_TERRACOTTA -> new Rule(on, intWeight, min, max);
            case NOTE_BLOCK -> new Rule(on, 1, min, max); // junk: possible, but rare next to the strong items
            case WARPED_HYPHAE -> off(intWeight, min, max);
        };
    }

    /** A whole cube: rules out slabs, stairs, panes, fences, walls, carpets, torches, shulker boxes... */
    private static boolean isFullBlock(Block block) {
        try {
            return !block.hasDynamicShape() && block.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            return false; // a modded block that needs a real level for its shape
        }
    }

    private static Rule gearRule(RandomizerSource source, Item item, ItemStack stack) {
        double power = gearPower(item);
        return switch (source) {
            case GLAZED_TERRACOTTA -> new Rule(power <= IRON_POWER,
                    Math.max(1, (int) Math.round(BASE_WEIGHT * Math.pow(GLAZED_GEAR_DECAY, Math.min(power, 10)) * rarityMultiplier(stack))), 1, 1);
            case NOTE_BLOCK -> new Rule(power <= DIAMOND_POWER,
                    Math.max(1, (int) Math.round(10 * Math.pow(NOTE_BLOCK_GEAR_GROWTH, Math.min(power, 10)))), 1, 1);
            case WARPED_HYPHAE -> off(10, 1, 1);
        };
    }

    /** wood/leather 0, stone 2, iron 4, diamond 6-6.5, netherite above. */
    private static double gearPower(Item item) {
        if (item instanceof ArmorItem armor) {
            ArmorMaterial material = armor.getMaterial();
            if (armor.getToughness() > ArmorMaterials.DIAMOND.getToughness() || material.getKnockbackResistance() > 0) return Double.MAX_VALUE;
            int total = 0;
            for (ArmorItem.Type type : ArmorItem.Type.values()) total += material.getDefenseForType(type);
            return Math.max(0, (total - 7) / 2.0); // leather 0, gold 2, chainmail 2.5, iron 4, diamond 6.5
        }
        if (item instanceof ShieldItem) return 2;
        if (item instanceof ElytraItem) return 5;
        if (item instanceof TridentItem) return 7;
        if (item instanceof TieredItem tiered) {
            Tier tier = tiered.getTier();
            return tier.getLevel() + tier.getAttackDamageBonus(); // wood/gold 0, stone 2, iron 4, diamond 6, netherite 8
        }
        if (item instanceof CrossbowItem) return 2.5;
        if (item instanceof ProjectileWeaponItem) return 1.5;
        if (item instanceof ShearsItem) return 0.5;
        return 1;
    }

    /**
     * Hyphae: all food up to golden apple. Glazed terracotta: only light snacks (apple, carrot, cookie, berries...).
     * Note block: just the golden apple.
     */
    @SuppressWarnings("deprecation")
    private static Rule foodRule(RandomizerSource source, Item item) {
        FoodProperties food = item.getFoodProperties();
        if (food == null || item == Items.ENCHANTED_GOLDEN_APPLE) return off(1, 1, 1);
        if (item == Items.GOLDEN_APPLE) {
            return switch (source) {
                case WARPED_HYPHAE -> new Rule(true, 2, 1, 1);
                case NOTE_BLOCK -> new Rule(true, 10, 1, 1);
                case GLAZED_TERRACOTTA -> off(2, 1, 1);
            };
        }
        // hunger + saturation points: cookie 2.4, apple 6.4, bread 11, steak 20.8
        double points = food.getNutrition() * (1 + 2 * food.getSaturationModifier());
        double weight = 60 / (1 + points / 6);
        boolean harmful = food.getEffects().stream().anyMatch(effect -> effect.getFirst().getEffect().getCategory() == MobEffectCategory.HARMFUL);
        if (harmful) weight /= 2;
        int max = Mth.clamp((int) Math.round(weight / 10), 1, Math.min(MAX_DEFAULT_COUNT, item.getMaxStackSize()));
        Rule rule = new Rule(true, Math.max(1, (int) Math.round(weight)), (max + 1) / 2, max);
        return switch (source) {
            case WARPED_HYPHAE -> rule;
            // chorus fruit teleports: no escaping a fight with a snack
            case GLAZED_TERRACOTTA -> food.getNutrition() <= 4 && !harmful && item != Items.CHORUS_FRUIT ? rule : off(rule);
            case NOTE_BLOCK -> off(rule);
        };
    }

    private static Rule potionRule(RandomizerSource source, Potion potion) {
        double power = 0;
        boolean levelTwo = false;
        for (MobEffectInstance effect : potion.getEffects()) {
            MobEffect type = effect.getEffect();
            double factor = STRONG_EFFECTS.contains(type) ? 2 : type.getCategory() == MobEffectCategory.HARMFUL ? 1.5 : 1;
            power += factor * (effect.getAmplifier() + 1);
            if (effect.getAmplifier() > 0) levelTwo = true;
            if (!type.isInstantenous() && effect.getDuration() > 3600) power += 0.5;
        }
        int rarer = Math.max(1, (int) Math.round(40 / power));
        return switch (source) {
            case WARPED_HYPHAE -> new Rule(!levelTwo, rarer, 1, 1);
            case NOTE_BLOCK -> new Rule(true, Math.max(1, (int) Math.round(5 * power)), 1, 1);
            case GLAZED_TERRACOTTA -> off(rarer, 1, 1);
        };
    }

    private static Rule enchantmentRule(RandomizerSource source, Enchantment enchantment, int level) {
        int rarity = enchantment.getRarity().getWeight(); // common 10, uncommon 5, rare 2, very rare 1
        int rarer = Math.max(1, (int) Math.round(rarity * 4.0 / (level * level)));
        if (enchantment.isCurse()) return off(rarer, 1, 1);
        return switch (source) {
            case WARPED_HYPHAE -> new Rule(!enchantment.isTreasureOnly() && level <= 2, rarer, 1, 1);
            case NOTE_BLOCK -> new Rule(true, Math.max(1, (int) Math.round(40.0 * level / enchantment.getMaxLevel())), 1, 1);
            case GLAZED_TERRACOTTA -> off(rarer, 1, 1);
        };
    }

    private static double rarityMultiplier(ItemStack stack) {
        double multiplier = switch (stack.getRarity()) {
            case COMMON -> 1.0;
            case UNCOMMON -> 0.5;
            case RARE -> 0.25;
            case EPIC -> 0.1;
        };
        return stack.is(ModTags.RANDOM_DROP_RARE) ? multiplier * 0.25 : multiplier;
    }

    private static Rule off(int weight, int min, int max) {
        return new Rule(false, weight, min, max);
    }

    private static Rule off(Rule rule) {
        return new Rule(false, rule.weight(), rule.min(), rule.max());
    }

    private DropCandidates() {}
}
