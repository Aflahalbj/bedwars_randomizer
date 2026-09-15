package com.bedwarsrandomizer.game;

import com.bedwarsrandomizer.drop.ModTags;
import com.bedwarsrandomizer.drop.RandomizerSource;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A selection block area holding randomizer blocks. Broken randomizer blocks are put back every refill; a floating
 * text above the area's highest block counts down to the next refill.
 */
public final class RefillArea {
    public static final String DISPLAY_TAG = "bwr_refill";

    private final BoundingBox box;
    private final EnumMap<RandomizerSource, Map<BlockPos, BlockState>> blocks;
    private final Vec3 displayPos;
    @Nullable
    private UUID displayId;

    private RefillArea(BoundingBox box, EnumMap<RandomizerSource, Map<BlockPos, BlockState>> blocks, Vec3 displayPos) {
        this.box = box;
        this.blocks = blocks;
        this.displayPos = displayPos;
    }

    /** Remembers the randomizer blocks inside {@code box} as they are now; null if there are none. */
    @Nullable
    public static RefillArea scan(ServerLevel level, BoundingBox box) {
        EnumMap<RandomizerSource, Map<BlockPos, BlockState>> blocks = new EnumMap<>(RandomizerSource.class);
        int top = box.minY();
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            BlockState state = level.getBlockState(pos);
            RandomizerSource source = state.is(ModTags.RANDOM_DROP_BLOCKS) ? RandomizerSource.of(state) : null;
            if (source == null) continue;
            top = Math.max(top, pos.getY());
            blocks.computeIfAbsent(source, s -> new LinkedHashMap<>()).put(pos.immutable(), state);
        }
        if (blocks.isEmpty()) return null;
        // one block above the highest randomizer block, over the middle of the area
        Vec3 displayPos = new Vec3((box.minX() + box.maxX() + 1) / 2.0, top + 1, (box.minZ() + box.maxZ() + 1) / 2.0);
        return new RefillArea(box, blocks, displayPos);
    }

    public BoundingBox box() {
        return box;
    }

    public Set<RandomizerSource> sources() {
        return blocks.keySet();
    }

    public Set<BlockPos> positions(RandomizerSource source) {
        return blocks.getOrDefault(source, Map.of()).keySet();
    }

    /** Puts back every block of this kind whose spot is empty again. Returns how many were placed. */
    public int refill(ServerLevel level, RandomizerSource source) {
        int placed = 0;
        for (Map.Entry<BlockPos, BlockState> entry : blocks.getOrDefault(source, Map.of()).entrySet()) {
            BlockState current = level.getBlockState(entry.getKey());
            if (current.isAir() || current.canBeReplaced()) {
                level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
                placed++;
            }
        }
        return placed;
    }

    @Nullable
    public UUID displayId() {
        return displayId;
    }

    public void updateDisplay(ServerLevel level, Map<RandomizerSource, Integer> secondsLeft) {
        // an unloaded area can't find its display; creating another one there would pile up copies
        if (!level.isPositionEntityTicking(BlockPos.containing(displayPos))) return;
        Component text = text(secondsLeft);
        Entity display = displayId == null ? null : level.getEntity(displayId);
        if (display == null) {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", "minecraft:text_display");
            tag.putString("billboard", "center");
            tag.putBoolean("see_through", true); // readable through the blocks around it
            tag.putString("text", Component.Serializer.toJson(text));
            ListTag tags = new ListTag();
            tags.add(StringTag.valueOf(DISPLAY_TAG));
            tag.put("Tags", tags);
            display = EntityType.loadEntityRecursive(tag, level, entity -> {
                entity.moveTo(displayPos.x, displayPos.y, displayPos.z, 0, 0);
                return entity;
            });
            if (display == null) return;
            displayId = display.getUUID(); // before adding: GameEvents drops refill texts it doesn't know
            level.addFreshEntity(display);
        } else {
            CompoundTag tag = display.saveWithoutId(new CompoundTag());
            tag.putString("text", Component.Serializer.toJson(text));
            display.load(tag);
        }
    }

    public void removeDisplay(ServerLevel level) {
        if (displayId == null) return;
        Entity display = level.getEntity(displayId);
        if (display != null) display.discard();
        displayId = null;
    }

    private Component text(Map<RandomizerSource, Integer> secondsLeft) {
        Set<Integer> distinct = new HashSet<>();
        for (RandomizerSource source : blocks.keySet()) distinct.add(secondsLeft.getOrDefault(source, 0));
        if (distinct.size() == 1) {
            return Component.literal("Refill in ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(distinct.iterator().next() + "s").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        }
        MutableComponent text = Component.literal("Refill").withStyle(ChatFormatting.AQUA);
        for (RandomizerSource source : blocks.keySet()) {
            text.append(Component.literal("\n" + label(source) + ": ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(secondsLeft.getOrDefault(source, 0) + "s").withStyle(ChatFormatting.WHITE));
        }
        return text;
    }

    private static String label(RandomizerSource source) {
        return switch (source) {
            case GLAZED_TERRACOTTA -> "Glazed";
            case WARPED_HYPHAE -> "Hyphae";
            case NOTE_BLOCK -> "Note block";
        };
    }
}
