package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.arena.Arena;
import com.bedwarsrandomizer.arena.ArenaData;
import com.bedwarsrandomizer.block.selection.SelectionBlock;
import com.bedwarsrandomizer.block.teambed.TeamBedBlock;
import com.bedwarsrandomizer.drop.*;
import com.bedwarsrandomizer.drop.DropCandidates.Candidate;
import com.bedwarsrandomizer.drop.RandomizerSettings.Rule;
import com.bedwarsrandomizer.fireball.BwrFireball;
import com.bedwarsrandomizer.game.TntEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.function.Consumer;

/**
 * Dev-only check, enabled with {@code gradlew runClient -ParenaTest}: default drop rules of every randomizer block,
 * the pasted arena and its block protection, glazed terracotta mining, fireballs (glass, damage), self-lighting TNT,
 * enchanted books applied by clicking, and screenshots of the arena and the randomizer settings screen.
 */
@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID, value = Dist.CLIENT)
public final class ArenaTestHarness {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("bwr.arenaTest", "false"));
    private static final BlockPos ARENA_MIN = new BlockPos(-93, 44, -93);
    private static final BlockPos ARENA_MAX = new BlockPos(93, 115, 92);
    private static final BlockPos FIREBALL_TARGET = new BlockPos(0, 130, 40);
    private static final BlockPos TNT_SUPPORT = new BlockPos(20, 130, 40);
    /** The stone platform the fireball damage test builds. */
    private static final BlockPos PLATFORM = new BlockPos(-20, 130, 40);
    private static Vec3 snowballStart = Vec3.ZERO;

    private static int ticks;
    private static BlockPos firstGlazed;
    private static BlockPos firstBed;
    private static float healthBefore;
    private static boolean tntPrimedOk;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        confirmExperimentalWarning(mc);
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;

        switch (++ticks) {
            case 40 -> onServer(mc, ArenaTestHarness::checkDrops);
            case 50 -> onServer(mc, ArenaTestHarness::checkPaste);
            case 90 -> onServer(mc, ArenaTestHarness::checkProtection);
            case 95 -> onServer(mc, ArenaTestHarness::checkGlazedMining);
            case 100 -> onServer(mc, ArenaTestHarness::launchFireball);
            case 160 -> onServer(mc, ArenaTestHarness::checkFireball);
            case 165 -> onServer(mc, ArenaTestHarness::launchFireballAtPlayer);
            case 190 -> onServer(mc, ArenaTestHarness::checkFireballDamage);
            case 195 -> onServer(mc, player -> {
                checkEnchantBook(player);
                placeTnt(player);
            });
            case 200 -> onServer(mc, ArenaTestHarness::throwSnowball);
            case 205 -> onServer(mc, ArenaTestHarness::checkTntPrimed);
            case 212 -> onServer(mc, ArenaTestHarness::checkSnowball);
            case 215 -> onServer(mc, ArenaTestHarness::launchFirework);
            case 245 -> onServer(mc, ArenaTestHarness::checkFirework);
            case 250 -> onServer(mc, ArenaTestHarness::standNearTnt);
            case 262 -> onServer(mc, ArenaTestHarness::checkTntDamageAndGlass);
            case 285 -> onServer(mc, ArenaTestHarness::checkTntExploded);
            case 290 -> onServer(mc, player -> {
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(player.serverLevel(), 0.5, 150, 120.5, 180, 30);
            });
            case 360 -> shot(mc, "arena_01_overview.png");
            case 370 -> onServer(mc, player -> player.getServer().getCommands()
                    .performPrefixedCommand(player.createCommandSourceStack().withPermission(4), "bwr setting randomizer"));
            case 410 -> shot(mc, "arena_02_randomizer.png");
            case 415 -> {
                // click the "Chance" column title: sort by chance, highest first
                if (mc.screen instanceof RandomizerScreen screen) screen.mouseClicked(screen.width / 2 - 178 + 155, 68, 0);
            }
            case 440 -> shot(mc, "arena_03_randomizer_sorted.png");
            case 445 -> mc.stop();
            default -> {}
        }
    }

    /** The datapack arena dimension makes Forge ask once per world about "experimental settings". */
    private static void confirmExperimentalWarning(Minecraft mc) {
        if (mc.screen instanceof ConfirmScreen confirm
                && confirm.getTitle().getContents() instanceof TranslatableContents title
                && title.getKey().equals("selectWorld.backupQuestion.experimental")) {
            for (var child : confirm.children()) {
                if (child instanceof Button button && button.getMessage().equals(CommonComponents.GUI_PROCEED)) {
                    BedwarsRandomizer.LOGGER.info("[arenaTest] confirming the experimental settings warning");
                    button.onPress();
                    return;
                }
            }
        }
    }

    private static void checkDrops(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<Candidate> candidates = DropCandidates.get(level.enabledFeatures());
        Map<String, Candidate> byId = new HashMap<>();
        for (Candidate candidate : candidates) byId.put(candidate.id(), candidate);
        List<String> problems = new ArrayList<>();

        for (Candidate candidate : candidates) {
            Rule rule = candidate.defaults(RandomizerSource.GLAZED_TERRACOTTA);
            if (!rule.enabled()) continue;
            if (candidate.item() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (block instanceof SlabBlock || block instanceof StairBlock || block instanceof IronBarsBlock || block instanceof FenceBlock
                        || block instanceof WallBlock || block instanceof CarpetBlock || block instanceof TorchBlock) {
                    problems.add("glazed has a non-full block: " + candidate.id());
                }
            }
            if (rule.max() > DropCandidates.MAX_DEFAULT_COUNT && candidate.item() != Items.WHITE_WOOL) problems.add("glazed max above 5: " + candidate.id());
        }
        expect(problems, byId, RandomizerSource.GLAZED_TERRACOTTA, true, "fire_charge", "arrow", "snowball", "white_wool", "glass", "iron_sword", "bow",
                "apple", "cookie");
        expect(problems, byId, RandomizerSource.GLAZED_TERRACOTTA, false, "cooked_beef", "golden_apple", "chorus_fruit");
        expect(problems, byId, RandomizerSource.GLAZED_TERRACOTTA, false, "ender_pearl", "totem_of_undying", "flint_and_steel", "obsidian", "tnt",
                "red_wool", "diamond_sword", "oak_slab", "stick", "glass_pane", "elytra");
        expect(problems, byId, RandomizerSource.NOTE_BLOCK, true, "totem_of_undying", "ender_pearl", "obsidian", "tnt", "diamond_sword",
                "diamond_chestplate", "splash_potion#minecraft:strong_healing", "enchanted_book#minecraft:sharpness/5", "golden_apple",
                "milk_bucket");
        expect(problems, byId, RandomizerSource.GLAZED_TERRACOTTA, false, "milk_bucket");
        expect(problems, byId, RandomizerSource.NOTE_BLOCK, false, "netherite_sword", "flint_and_steel", "elytra");
        expect(problems, byId, RandomizerSource.WARPED_HYPHAE, true, "bread", "golden_apple", "splash_potion#minecraft:healing",
                "enchanted_book#minecraft:sharpness/1");
        expect(problems, byId, RandomizerSource.WARPED_HYPHAE, false, "enchanted_golden_apple", "splash_potion#minecraft:strong_healing",
                "enchanted_book#minecraft:sharpness/5", "enchanted_book#minecraft:mending/1", "iron_sword", "elytra");
        for (String randomizer : List.of("red_glazed_terracotta", "warped_hyphae", "note_block")) {
            if (byId.containsKey("minecraft:" + randomizer)) problems.add("randomizer block is listed as a drop: " + randomizer);
        }

        // red glazed terracotta: colored blocks only in red, never more than 5
        RandomDropPool glazed = RandomDropPool.get(level, RandomizerSource.GLAZED_TERRACOTTA);
        Map<String, Integer> glazedCounts = new TreeMap<>();
        for (int i = 0; i < 20000; i++) {
            ItemStack stack = glazed.roll(level.random, DyeColor.RED);
            if (stack.getCount() > DropCandidates.MAX_DEFAULT_COUNT && !stack.is(ItemTags.WOOL)) problems.add("glazed rolled " + stack.getCount() + "x " + key(stack.getItem()));
            if (stack.is(ItemTags.WOOL) && (stack.getCount() < 4 || stack.getCount() > 10)) problems.add("glazed rolled " + stack.getCount() + " wool");
            DyeColor color = ColoredBlocks.colorOf(stack.getItem());
            if (color != null && color != DyeColor.RED) problems.add("red glazed terracotta dropped " + key(stack.getItem()));
            glazedCounts.merge(key(stack.getItem()), 1, Integer::sum);
        }
        StringBuilder glazedSamples = new StringBuilder();
        for (String sample : List.of("red_wool", "red_concrete", "red_stained_glass", "glass", "end_stone", "iron_sword", "arrow", "fire_charge", "ender_pearl", "obsidian")) {
            glazedSamples.append(' ').append(sample).append('=').append(glazedCounts.getOrDefault(sample, 0));
        }
        BedwarsRandomizer.LOGGER.info("[arenaTest] red glazed, 20000 rolls:{}", glazedSamples);
        String mostCommon = glazedCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        if (!mostCommon.equals("red_wool")) problems.add("most common red glazed drop is " + mostCommon + ", not red_wool");

        // warped hyphae: only food, potions and enchantments
        RandomDropPool hyphae = RandomDropPool.get(level, RandomizerSource.WARPED_HYPHAE);
        EnumMap<DropCategory, Integer> hyphaeCategories = new EnumMap<>(DropCategory.class);
        Map<String, Integer> hyphaeCounts = new HashMap<>();
        for (int i = 0; i < 5000; i++) {
            ItemStack stack = hyphae.roll(level.random, null);
            DropCategory category = hyphae.categoryOf(stack.getItem());
            if (category != DropCategory.FOOD && category != DropCategory.POTION && category != DropCategory.ENCHANTMENT) {
                problems.add("hyphae dropped " + key(stack.getItem()));
                continue;
            }
            hyphaeCategories.merge(category, 1, Integer::sum);
            hyphaeCounts.merge(stack.getHoverName().getString(), 1, Integer::sum);
        }
        BedwarsRandomizer.LOGGER.info("[arenaTest] hyphae, 5000 rolls: {} golden apples={} cookies={}", hyphaeCategories,
                hyphaeCounts.getOrDefault("Golden Apple", 0), hyphaeCounts.getOrDefault("Cookie", 0));

        // note block: mostly strong items, junk blocks still possible
        RandomDropPool note = RandomDropPool.get(level, RandomizerSource.NOTE_BLOCK);
        int rolls = 5000, junk = 0, strong = 0;
        EnumMap<DropCategory, Integer> noteCategories = new EnumMap<>(DropCategory.class);
        for (int i = 0; i < rolls; i++) {
            ItemStack stack = note.roll(level.random, null);
            Item item = stack.getItem();
            DropCategory category = note.categoryOf(item);
            if (category != null) noteCategories.merge(category, 1, Integer::sum);
            if (category == DropCategory.BLOCK && item != Items.OBSIDIAN && item != Items.TNT) junk++;
            if (item == Items.OBSIDIAN || item == Items.TNT || item == Items.TOTEM_OF_UNDYING || item == Items.ENDER_PEARL || item == Items.GOLDEN_APPLE
                    || key(item).startsWith("diamond_") || item instanceof PotionItem || item instanceof EnchantedBookItem) {
                strong++;
            }
        }
        if (junk * 100 / rolls > 25) problems.add("note block junk share " + junk * 100 / rolls + "%");
        if (strong * 100 / rolls < 40) problems.add("note block strong share " + strong * 100 / rolls + "%");
        BedwarsRandomizer.LOGGER.info("[arenaTest] note block, {} rolls: {} junk blocks={}% strong={}%", rolls, noteCategories,
                junk * 100 / rolls, strong * 100 / rolls);

        problems.stream().limit(20).forEach(problem -> BedwarsRandomizer.LOGGER.error("[arenaTest] drops: {}", problem));
        BedwarsRandomizer.LOGGER.info("[arenaTest] drops {} ({} candidates, {} problems)", problems.isEmpty() ? "PASS" : "FAIL",
                candidates.size(), problems.size());
    }

    private static void expect(List<String> problems, Map<String, Candidate> byId, RandomizerSource source, boolean on, String... ids) {
        for (String id : ids) {
            Candidate candidate = byId.get("minecraft:" + id);
            if (candidate == null) problems.add(source.key() + ": missing " + id);
            else if (candidate.defaults(source).enabled() != on) problems.add(source.key() + ": " + id + " should be " + (on ? "on" : "off"));
        }
    }

    private static void checkPaste(ServerPlayer player) {
        player.getServer().setDifficulty(Difficulty.NORMAL, true);
        ServerLevel arena = Arena.level(player.getServer());
        if (arena == null) {
            BedwarsRandomizer.LOGGER.error("[arenaTest] paste FAIL: no arena dimension");
            return;
        }
        int glazed = 0, bedParts = 0, selections = 0;
        for (BlockPos pos : BlockPos.betweenClosed(ARENA_MIN, ARENA_MAX)) {
            BlockState state = arena.getBlockState(pos);
            if (state.getBlock() instanceof GlazedTerracottaBlock) {
                glazed++;
                if (firstGlazed == null) firstGlazed = pos.immutable();
            } else if (state.getBlock() instanceof TeamBedBlock) {
                bedParts++;
                if (firstBed == null) firstBed = pos.immutable();
            } else if (state.getBlock() instanceof SelectionBlock) {
                selections++;
            }
        }
        boolean ok = glazed == 128 && bedParts == 16 && selections == 16 && arena.getBlockState(new BlockPos(0, 99, 0)).is(Blocks.BROWN_STAINED_GLASS);
        BedwarsRandomizer.LOGGER.info("[arenaTest] paste {} (glazed={} expect 128, bed parts={} expect 16, selection blocks={} expect 16)",
                ok ? "PASS" : "FAIL", glazed, bedParts, selections);

        // island order: random first island, the second always across the map, all 8 used
        List<DyeColor> ring = List.of(DyeColor.LIME, DyeColor.YELLOW, DyeColor.CYAN, DyeColor.WHITE, DyeColor.PINK, DyeColor.GRAY, DyeColor.RED, DyeColor.BLUE);
        Set<DyeColor> firstIslands = EnumSet.noneOf(DyeColor.class);
        boolean spread = true;
        List<String> examples = new ArrayList<>();
        Random random = new Random(); // one generator, like the game uses: new Random(seed) for seeds 0, 1, 2... starts alike
        for (int seed = 0; seed < 200; seed++) {
            List<DyeColor> order = com.bedwarsrandomizer.game.BedwarsGame.randomIslandOrder(random);
            firstIslands.add(order.get(0));
            if (Math.floorMod(ring.indexOf(order.get(0)) - ring.indexOf(order.get(1)), 8) != 4 || EnumSet.copyOf(order).size() != 8) spread = false;
            if (seed < 3) examples.add(order.subList(0, 4).stream().map(DyeColor::getName).toList().toString());
        }
        BedwarsRandomizer.LOGGER.info("[arenaTest] islands {} (different first islands={}/8, second always opposite={}, e.g. {})",
                firstIslands.size() == 8 && spread ? "PASS" : "FAIL", firstIslands.size(), spread, examples);

        Arena.teleport(player, arena);
        player.setGameMode(GameType.SURVIVAL);
    }

    private static void checkProtection(ServerPlayer player) {
        ServerLevel arena = player.serverLevel();
        if (!Arena.isArena(arena)) {
            BedwarsRandomizer.LOGGER.error("[arenaTest] protection FAIL: player is not in the arena");
            return;
        }
        BlockPos glass = new BlockPos(0, 99, 0);
        boolean mapBlockKept = !player.gameMode.destroyBlock(glass) && arena.getBlockState(glass).is(Blocks.BROWN_STAINED_GLASS);

        BlockPos support = BlockPos.betweenClosedStream(glass.offset(-4, 0, -4), glass.offset(4, 0, 4))
                .filter(pos -> Math.abs(pos.getX()) + Math.abs(pos.getZ()) >= 2)
                .filter(pos -> !arena.getBlockState(pos).isAir() && arena.getBlockState(pos.above()).isAir())
                .findFirst().map(BlockPos::immutable).orElse(glass.offset(2, 0, 0));
        BlockPos placed = support.above();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHITE_WOOL, 4));
        player.gameMode.useItemOn(player, arena, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(support).relative(Direction.UP, 0.5), Direction.UP, support, false));
        boolean placedOk = arena.getBlockState(placed).is(Blocks.WHITE_WOOL);
        boolean placedBroken = player.gameMode.destroyBlock(placed) && arena.getBlockState(placed).isAir();

        boolean glazedBroken = firstGlazed != null && player.gameMode.destroyBlock(firstGlazed) && arena.getBlockState(firstGlazed).isAir();
        boolean bedBroken = firstBed != null && player.gameMode.destroyBlock(firstBed)
                && !(arena.getBlockState(firstBed).getBlock() instanceof TeamBedBlock);

        boolean ok = mapBlockKept && placedOk && placedBroken && glazedBroken && bedBroken;
        BedwarsRandomizer.LOGGER.info("[arenaTest] protection {} (map block kept={}, player block placed={} broken={}, glazed broken={}, bed broken={})",
                ok ? "PASS" : "FAIL", mapBlockKept, placedOk, placedBroken, glazedBroken, bedBroken);
    }

    /** Glazed terracotta: as slow as dirt by hand, faster with any pickaxe, drops by hand in its own color. */
    private static void checkGlazedMining(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = new BlockPos(0, 140, 40);
        BlockState glazed = Blocks.RED_GLAZED_TERRACOTTA.defaultBlockState();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        int handTicks = ticksToBreak(player, level, pos, glazed);
        int dirtTicks = ticksToBreak(player, level, pos, Blocks.DIRT.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WOODEN_PICKAXE));
        int woodPickTicks = ticksToBreak(player, level, pos, glazed);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        int ironPickTicks = ticksToBreak(player, level, pos, glazed);

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.setBlock(pos, glazed, Block.UPDATE_ALL);
        boolean broken = player.gameMode.destroyBlock(pos) && level.getBlockState(pos).isAir();
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2));
        String dropped = drops.stream().map(e -> e.getItem().getCount() + "x " + key(e.getItem().getItem())).toList().toString();

        boolean ok = handTicks == dirtTicks && woodPickTicks < handTicks && ironPickTicks < woodPickTicks && broken && !drops.isEmpty();
        BedwarsRandomizer.LOGGER.info("[arenaTest] glazed mining {} (ticks: hand={} dirt by hand={} wooden pickaxe={} iron pickaxe={}; broken by hand={} drops={})",
                ok ? "PASS" : "FAIL", handTicks, dirtTicks, woodPickTicks, ironPickTicks, broken, dropped);
        drops.forEach(Entity::discard);
    }

    private static int ticksToBreak(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        float progress = state.getDestroyProgress(player, level, pos);
        return progress <= 0 ? Integer.MAX_VALUE : (int) Math.ceil(1.0F / progress);
    }

    /** A 5x5 player-placed glass wall, player-placed wool behind and in front of it, a map block in front; fireball into the wall. */
    private static void launchFireball(ServerPlayer player) {
        ServerLevel arena = player.serverLevel();
        ArenaData data = ArenaData.get(arena);
        BlockPos c = FIREBALL_TARGET;
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                place(arena, data, c.offset(x, y, 0), Blocks.GLASS, true);
            }
        }
        place(arena, data, c.offset(0, 0, 1), Blocks.WHITE_WOOL, true);
        place(arena, data, c.offset(1, 0, -1), Blocks.RED_WOOL, true);
        place(arena, data, c.offset(-1, 0, -1), Blocks.BLUE_WOOL, false);

        BwrFireball fireball = new BwrFireball(arena, player, new Vec3(0, 0, 1));
        fireball.setPos(c.getX() + 0.5, c.getY(), c.getZ() - 6.5);
        fireball.setDeltaMovement(0, 0, 1);
        arena.addFreshEntity(fireball);
    }

    private static void checkFireball(ServerPlayer player) {
        ServerLevel arena = player.serverLevel();
        BlockPos c = FIREBALL_TARGET;
        int glass = 0;
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                if (arena.getBlockState(c.offset(x, y, 0)).is(Tags.Blocks.GLASS)) glass++;
            }
        }
        boolean behindKept = arena.getBlockState(c.offset(0, 0, 1)).is(Blocks.WHITE_WOOL);
        boolean playerWoolBroken = arena.getBlockState(c.offset(1, 0, -1)).isAir();
        boolean mapWoolKept = arena.getBlockState(c.offset(-1, 0, -1)).is(Blocks.BLUE_WOOL);
        boolean exploded = arena.getEntitiesOfClass(BwrFireball.class, new AABB(c).inflate(20)).isEmpty();
        boolean ok = glass == 25 && behindKept && playerWoolBroken && mapWoolKept && exploded;
        BedwarsRandomizer.LOGGER.info("[arenaTest] fireball {} (exploded={}, glass left={}/25, wool behind glass kept={}, player wool in front broken={}, map wool kept={})",
                ok ? "PASS" : "FAIL", exploded, glass, behindKept, playerWoolBroken, mapWoolKept);
    }

    /** On a platform in the void (map blocks near spawn would stop the fireball early), a fireball flies into the player. */
    private static void launchFireballAtPlayer(ServerPlayer player) {
        ServerLevel arena = player.serverLevel();
        BlockPos platform = new BlockPos(-20, 130, 40);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) arena.setBlock(platform.offset(x, 0, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        player.teleportTo(arena, platform.getX() + 0.5, platform.getY() + 1, platform.getZ() + 0.5, 0, 0);
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        healthBefore = player.getHealth();
        BwrFireball fireball = new BwrFireball(arena, player, new Vec3(0, 0, 1));
        fireball.setOwner(null); // a thrower can't be hit by their own fireball right away
        fireball.setPos(platform.getX() + 0.5, platform.getY() + 1.5, platform.getZ() + 0.5 - 6);
        fireball.setDeltaMovement(0, 0, 1);
        arena.addFreshEntity(fireball);
    }

    private static void checkFireballDamage(ServerPlayer player) {
        float damage = healthBefore - player.getHealth();
        boolean ok = damage > 0 && damage <= 9;
        BedwarsRandomizer.LOGGER.info("[arenaTest] fireball damage {} ({} HP = {} hearts taken point blank, no armor, difficulty {})",
                ok ? "PASS" : "FAIL", damage, damage / 2, player.level().getDifficulty());
        player.setHealth(player.getMaxHealth());
        Arena.teleport(player, player.serverLevel());
    }

    /** A Sharpness III book clicked onto a sword enchants it; a Protection book on a sword does nothing special. */
    private static void checkEnchantBook(ServerPlayer player) {
        player.setGameMode(GameType.SURVIVAL);
        var menu = player.inventoryMenu;
        player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD)); // hotbar slot 0 = menu slot 36
        menu.setCarried(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(Enchantments.SHARPNESS, 3)));
        menu.clicked(36, 0, ClickType.PICKUP, player);
        int sharpness = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, player.getInventory().getItem(0));
        boolean bookUsed = menu.getCarried().isEmpty();

        menu.setCarried(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(Enchantments.ALL_DAMAGE_PROTECTION, 2)));
        menu.clicked(36, 0, ClickType.PICKUP, player);
        ItemStack slot = player.getInventory().getItem(0);
        boolean wrongBookNotApplied = !(slot.is(Items.IRON_SWORD) && EnchantmentHelper.getItemEnchantmentLevel(Enchantments.ALL_DAMAGE_PROTECTION, slot) > 0);
        menu.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(0, ItemStack.EMPTY);

        boolean ok = sharpness == 3 && bookUsed && wrongBookNotApplied;
        BedwarsRandomizer.LOGGER.info("[arenaTest] enchant book {} (sword sharpness={} book used up={} protection book not applied to sword={})",
                ok ? "PASS" : "FAIL", sharpness, bookUsed, wrongBookNotApplied);
    }

    private static void placeTnt(ServerPlayer player) {
        ServerLevel arena = player.serverLevel();
        ArenaData data = ArenaData.get(arena);
        place(arena, data, TNT_SUPPORT, Blocks.STONE, true);
        // player-placed glass wall 2 blocks away with wool behind it, wool right next to the TNT, a block to stand on
        for (int x = -2; x <= 2; x++) {
            for (int y = 1; y <= 3; y++) place(arena, data, TNT_SUPPORT.offset(x, y, 2), Blocks.GLASS, true);
        }
        place(arena, data, TNT_SUPPORT.offset(0, 1, 3), Blocks.WHITE_WOOL, true);
        place(arena, data, TNT_SUPPORT.offset(1, 1, 0), Blocks.RED_WOOL, true);
        place(arena, data, TNT_SUPPORT.offset(-4, 0, 0), Blocks.STONE, true);
        player.setGameMode(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TNT));
        player.gameMode.useItemOn(player, arena, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(TNT_SUPPORT).relative(Direction.UP, 0.5), Direction.UP, TNT_SUPPORT, false));
    }

    /** On the fireball platform: a snowball flies into the player. */
    private static void throwSnowball(ServerPlayer player) {
        ServerLevel arena = player.serverLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(arena, PLATFORM.getX() + 0.5, PLATFORM.getY() + 1, PLATFORM.getZ() + 0.5, 0, 0);
        player.setDeltaMovement(Vec3.ZERO);
        player.setHealth(player.getMaxHealth());
        healthBefore = player.getHealth();
        snowballStart = player.position();
        Snowball snowball = new Snowball(EntityType.SNOWBALL, arena);
        snowball.setPos(player.getX(), player.getY() + 1.2, player.getZ() - 4);
        snowball.setDeltaMovement(0, 0, 1.5);
        arena.addFreshEntity(snowball);
    }

    private static void checkSnowball(ServerPlayer player) {
        float damage = healthBefore - player.getHealth();
        double pushed = player.getZ() - snowballStart.z;
        // the 0.01 HP is usually healed again by natural regeneration before this check; the push shows the hit landed
        boolean ok = damage < 0.5F && pushed > 0.2;
        BedwarsRandomizer.LOGGER.info("[arenaTest] snowball {} (damage={} HP, pushed back {} blocks)", ok ? "PASS" : "FAIL", damage,
                String.format(Locale.ROOT, "%.2f", pushed));
    }

    private static void launchFirework(ServerPlayer player) {
        ServerLevel arena = player.serverLevel();
        player.teleportTo(arena, PLATFORM.getX() + 0.5, PLATFORM.getY() + 1, PLATFORM.getZ() + 0.5, 0, 0);
        player.setHealth(player.getMaxHealth());
        healthBefore = player.getHealth();
        ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
        CompoundTag fireworks = rocket.getOrCreateTagElement("Fireworks");
        fireworks.putByte("Flight", (byte) 0);
        CompoundTag explosion = new CompoundTag();
        explosion.putByte("Type", (byte) 1);
        explosion.putIntArray("Colors", new int[]{0xFF0000});
        ListTag explosions = new ListTag();
        explosions.add(explosion);
        fireworks.put("Explosions", explosions);
        arena.addFreshEntity(new FireworkRocketEntity(arena, player.getX(), player.getY() + 1, player.getZ(), rocket));
    }

    private static void checkFirework(ServerPlayer player) {
        boolean exploded = player.serverLevel().getEntitiesOfClass(FireworkRocketEntity.class, player.getBoundingBox().inflate(16)).isEmpty();
        boolean unharmed = player.getHealth() >= healthBefore;
        BedwarsRandomizer.LOGGER.info("[arenaTest] firework {} (exploded={} player unharmed={})", exploded && unharmed ? "PASS" : "FAIL", exploded, unharmed);
    }

    /** 4 blocks from the primed TNT, nothing in between. */
    private static void standNearTnt(ServerPlayer player) {
        BlockPos stand = TNT_SUPPORT.offset(-4, 1, 0);
        player.teleportTo(player.serverLevel(), stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, -90, 0);
        player.setHealth(player.getMaxHealth());
        healthBefore = player.getHealth();
    }

    private static void checkTntDamageAndGlass(ServerPlayer player) {
        ServerLevel arena = player.serverLevel();
        float damage = healthBefore - player.getHealth();
        int glass = 0;
        for (int x = -2; x <= 2; x++) {
            for (int y = 1; y <= 3; y++) {
                if (arena.getBlockState(TNT_SUPPORT.offset(x, y, 2)).is(Blocks.GLASS)) glass++;
            }
        }
        boolean behindKept = arena.getBlockState(TNT_SUPPORT.offset(0, 1, 3)).is(Blocks.WHITE_WOOL);
        boolean nextBroken = arena.getBlockState(TNT_SUPPORT.offset(1, 1, 0)).isAir();
        boolean exploded = arena.getEntitiesOfClass(PrimedTnt.class, new AABB(TNT_SUPPORT).inflate(4)).isEmpty();
        boolean ok = exploded && damage > 0 && damage <= 8 && glass == 15 && behindKept && nextBroken;
        BedwarsRandomizer.LOGGER.info("[arenaTest] tnt vs glass {} (exploded={} damage at 4 blocks={} HP, glass left={}/15, wool behind glass kept={}, wool next to tnt broken={})",
                ok ? "PASS" : "FAIL", exploded, damage, glass, behindKept, nextBroken);
        player.setHealth(player.getMaxHealth());
        Arena.teleport(player, arena);
    }

    private static void checkTntPrimed(ServerPlayer player) {
        ServerLevel arena = player.serverLevel();
        BlockPos tntPos = TNT_SUPPORT.above();
        List<PrimedTnt> primed = arena.getEntitiesOfClass(PrimedTnt.class, new AABB(tntPos).inflate(2));
        int fuse = primed.isEmpty() ? -1 : primed.get(0).getFuse();
        tntPrimedOk = arena.getBlockState(tntPos).isAir() && primed.size() == 1 && fuse > 30 && fuse <= TntEvents.FUSE_TICKS;
        BedwarsRandomizer.LOGGER.info("[arenaTest] tnt lit on place: block gone={} primed={} fuse left={}", arena.getBlockState(tntPos).isAir(), primed.size(), fuse);
    }

    private static void checkTntExploded(ServerPlayer player) {
        ServerLevel arena = player.serverLevel();
        boolean exploded = arena.getEntitiesOfClass(PrimedTnt.class, new AABB(TNT_SUPPORT).inflate(4)).isEmpty();
        BedwarsRandomizer.LOGGER.info("[arenaTest] tnt {} (lit on place={}, exploded within 4 s={})", tntPrimedOk && exploded ? "PASS" : "FAIL", tntPrimedOk, exploded);
    }

    private static void place(ServerLevel level, ArenaData data, BlockPos pos, Block block, boolean byPlayer) {
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
        if (byPlayer) data.markPlayerPlaced(pos);
    }

    private static String key(Item item) {
        return ForgeRegistries.ITEMS.getKey(item).getPath();
    }

    private static void onServer(Minecraft mc, Consumer<ServerPlayer> action) {
        IntegratedServer server = mc.getSingleplayerServer();
        UUID playerId = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) action.accept(player);
        });
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {});
        BedwarsRandomizer.LOGGER.info("[arenaTest] screenshot {}", name);
    }

    private ArenaTestHarness() {}
}
