package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.block.ModBlocks;
import com.bedwarsrandomizer.block.selection.SelectionBlock;
import com.bedwarsrandomizer.block.selection.SelectionBlockEntity;
import com.bedwarsrandomizer.block.selection.SelectionPasteProcessor;
import com.bedwarsrandomizer.block.selection.SelectionPaster;
import com.bedwarsrandomizer.block.teambed.TeamBedBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GlazedTerracottaBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;
import java.util.UUID;

/**
 * Dev-only check for selection blocks and team beds, enabled with {@code gradlew runClient -PselectionTest}.
 * Places four selection blocks (one per facing), builds an asymmetric test build in the north-facing one, runs Save,
 * logs every pasted block that doesn't match the expected rotated state, saves screenshots and quits.
 */
@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID, value = Dist.CLIENT)
public final class SelectionTestHarness {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("bwr.selectionTest", "false"));
    private static final BlockPos BASE = new BlockPos(0, 120, 60);
    private static final BlockPos OFFSET = new BlockPos(-2, 0, -7);
    private static final BlockPos SIZE = new BlockPos(5, 4, 5);
    private static final Direction[] FACINGS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    /** Source is red; the last target has no team, so it keeps the source colors. */
    private static final DyeColor[] TEAMS = {DyeColor.RED, DyeColor.BLUE, DyeColor.LIME, null};
    private static final BlockPos GLAZED = new BlockPos(1, 1, 2);
    private static final BlockPos BED_HEAD = new BlockPos(3, 1, 3);

    private static int ticks;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;

        ticks++;
        if (ticks == 40) {
            onServer(mc, SelectionTestHarness::buildAndPaste);
        } else if (ticks == 60) {
            onServer(mc, player -> {
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(player.serverLevel(), BASE.getX() + 10.5, BASE.getY() + 30, BASE.getZ() + 6.5, 0, 90);
            });
        } else if (ticks == 120) {
            shot(mc, "selection_01_top.png");
            onServer(mc, player -> {
                player.teleportTo(player.serverLevel(), BASE.getX() + 5.5, BASE.getY() + 4, BASE.getZ() + 6.5, 145, 25);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModBlocks.SELECTION_BLOCK_ITEM.get()));
                player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModBlocks.TEAM_BEDS.get(DyeColor.BLUE).get()));
            });
        } else if (ticks == 170) {
            shot(mc, "selection_02_close.png");
        } else if (ticks == 190) {
            mc.stop();
        }
    }

    private static void buildAndPaste(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        IntegratedServer server = (IntegratedServer) level.getServer();
        server.getPlayerList().op(player.getGameProfile());
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.setDayTime(6000);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);

        for (BlockPos pos : BlockPos.betweenClosed(BASE.offset(-12, -2, -12), BASE.offset(32, 8, 32))) {
            level.setBlock(pos, pos.getY() == BASE.getY() - 1 ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        SelectionBlockEntity source = null;
        for (int i = 0; i < FACINGS.length; i++) {
            BlockPos pos = blockPos(i);
            level.setBlock(pos, ModBlocks.SELECTION_BLOCK.get().defaultBlockState().setValue(SelectionBlock.FACING, FACINGS[i]), Block.UPDATE_ALL);
            SelectionBlockEntity selection = (SelectionBlockEntity) level.getBlockEntity(pos);
            selection.setSettings(new SelectionBlockEntity.Settings(OFFSET, SIZE, true, TEAMS[i]));
            if (i == 0) source = selection;
            // junk in the target areas must be replaced by the paste
            if (i > 0) level.setBlock(pos.offset(OFFSET.rotate(selection.rotation())).above(), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        // a selection block with another size must be left alone
        BlockPos odd = BASE.offset(40, 0, 0);
        level.setBlock(odd, ModBlocks.SELECTION_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        ((SelectionBlockEntity) level.getBlockEntity(odd)).setSettings(new SelectionBlockEntity.Settings(OFFSET, new BlockPos(3, 3, 3), true, DyeColor.RED));
        BlockPos oddMarker = odd.offset(OFFSET).above();
        level.setBlock(oddMarker, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);

        // asymmetric build in the north-facing source: a red corner, a lime row, stairs and a team bed facing north
        BlockPos origin = BASE.offset(OFFSET);
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) level.setBlock(origin.offset(x, 0, z), Blocks.WHITE_WOOL.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(origin, Blocks.RED_WOOL.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int y = 1; y < 4; y++) level.setBlock(origin.offset(0, y, 0), Blocks.RED_WOOL.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int x = 1; x < 5; x++) level.setBlock(origin.offset(x, 1, 0), Blocks.LIME_WOOL.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(origin.offset(2, 1, 2), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH), Block.UPDATE_CLIENTS);
        BlockState bed = ModBlocks.TEAM_BEDS.get(DyeColor.BLUE).get().defaultBlockState().setValue(TeamBedBlock.FACING, Direction.NORTH);
        level.setBlock(origin.offset(3, 1, 4), bed.setValue(TeamBedBlock.PART, BedPart.FOOT), Block.UPDATE_CLIENTS);
        level.setBlock(origin.offset(BED_HEAD), bed.setValue(TeamBedBlock.PART, BedPart.HEAD), Block.UPDATE_CLIENTS);
        level.setBlock(origin.offset(GLAZED), Blocks.RED_GLAZED_TERRACOTTA.defaultBlockState().setValue(GlazedTerracottaBlock.FACING, Direction.EAST), Block.UPDATE_CLIENTS);
        level.setBlock(origin.offset(4, 2, 0), Blocks.WHITE_GLAZED_TERRACOTTA.defaultBlockState(), Block.UPDATE_CLIENTS);

        SelectionPaster.saveAndPaste(player, source);
        verify(level, source);
        BedwarsRandomizer.LOGGER.info("[selectionTest] different-size selection block {} (its area untouched={})",
                level.getBlockState(oddMarker).is(Blocks.GLOWSTONE) ? "PASS" : "FAIL", level.getBlockState(oddMarker).is(Blocks.GLOWSTONE));

        BlockPos bedPos = origin.offset(BED_HEAD);
        level.getBlockState(bedPos).use(level, player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(bedPos), Direction.UP, bedPos, false));
        BedwarsRandomizer.LOGGER.info("[selectionTest] team bed: sleeping={} isBed={}", player.isSleeping(),
                level.getBlockState(bedPos).isBed(level, bedPos, player));
    }

    private static void verify(ServerLevel level, SelectionBlockEntity source) {
        int checked = 0, wrong = 0;
        for (int i = 1; i < FACINGS.length; i++) {
            SelectionBlockEntity target = (SelectionBlockEntity) level.getBlockEntity(blockPos(i));
            Rotation relative = SelectionBlockEntity.inverse(source.rotation()).getRotated(target.rotation());
            if (!target.settings().size().equals(SIZE)) {
                BedwarsRandomizer.LOGGER.error("[selectionTest] {} size not copied: {}", FACINGS[i], target.settings().size());
                wrong++;
            }
            for (int x = 0; x < SIZE.getX(); x++) for (int y = 0; y < SIZE.getY(); y++) for (int z = 0; z < SIZE.getZ(); z++) {
                BlockPos local = OFFSET.offset(x, y, z);
                BlockState expected = SelectionPasteProcessor.recolor(
                        level.getBlockState(source.getBlockPos().offset(local.rotate(source.rotation()))), TEAMS[i]).rotate(relative);
                BlockPos at = target.getBlockPos().offset(local.rotate(target.rotation()));
                BlockState actual = level.getBlockState(at);
                checked++;
                if (!actual.equals(expected)) {
                    wrong++;
                    BedwarsRandomizer.LOGGER.error("[selectionTest] {} at {}: expected {} got {}", FACINGS[i], at, expected, actual);
                }
            }
            // spelled out, independent of recolor(): the team's color, or the source's own colors without a team
            String color = TEAMS[i] == null ? null : TEAMS[i].getName();
            wrong += expectBlock(level, target, GLAZED, (color == null ? "red" : color) + "_glazed_terracotta");
            wrong += expectBlock(level, target, new BlockPos(4, 2, 0), (color == null ? "white" : color) + "_glazed_terracotta");
            wrong += expectBlock(level, target, BED_HEAD, (color == null ? "blue" : color) + "_team_bed");
        }
        BedwarsRandomizer.LOGGER.info("[selectionTest] {} ({} blocks checked, {} wrong)", wrong == 0 ? "PASS" : "FAIL", checked, wrong);
    }

    private static int expectBlock(ServerLevel level, SelectionBlockEntity target, BlockPos inBuild, String expectedPath) {
        BlockPos at = target.getBlockPos().offset(OFFSET.offset(inBuild).rotate(target.rotation()));
        String actual = ForgeRegistries.BLOCKS.getKey(level.getBlockState(at).getBlock()).getPath();
        BedwarsRandomizer.LOGGER.info("[selectionTest] {} team={} {} -> {}", target.facing(), target.settings().team(), expectedPath, actual);
        return actual.equals(expectedPath) ? 0 : 1;
    }

    private static BlockPos blockPos(int index) {
        return BASE.offset(index % 2 == 1 ? 20 : 0, 0, index >= 2 ? 20 : 0);
    }

    private static void onServer(Minecraft mc, java.util.function.Consumer<ServerPlayer> action) {
        IntegratedServer server = mc.getSingleplayerServer();
        UUID playerId = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) action.accept(player);
        });
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {});
        BedwarsRandomizer.LOGGER.info(String.format(Locale.ROOT, "[selectionTest] screenshot %s", name));
    }

    private SelectionTestHarness() {}
}
