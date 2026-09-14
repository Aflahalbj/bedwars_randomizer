package com.bedwarsrandomizer.replay;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.command.BwrCommand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.CriticalHitEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID)
public final class ReplayEvents {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BwrCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ReplayRecorder.get().tick(event.getServer());
            ClipAssembler.tick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ReplayRecorder.get().reset();
        ClipAssembler.reset();
        ReplayStorage.clear();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;

        ServerPlayer killer = null;
        if (event.getSource().getEntity() instanceof ServerPlayer direct) {
            killer = direct;
        } else if (victim.getKillCredit() instanceof ServerPlayer credited) {
            // knocked into the void / off a cliff by someone
            killer = credited;
        }
        if (killer != null && killer != victim) {
            ReplayRecorder.get().onKill(victim, killer, event.getSource());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCriticalHit(CriticalHitEvent event) {
        if (!(event.getTarget() instanceof ServerPlayer target)) return;
        boolean critical = event.getResult() == Event.Result.ALLOW
                || (event.getResult() == Event.Result.DEFAULT && event.isVanillaCritical());
        if (critical) {
            ReplayRecorder.get().markPendingHit(target.getUUID(), ActorFrame.FLAG_CRIT);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onAttack(AttackEntityEvent event) {
        if (!(event.getTarget() instanceof ServerPlayer target)) return;
        // same check vanilla uses for the enchanted-hit sparks
        if (EnchantmentHelper.getDamageBonus(event.getEntity().getMainHandItem(), target.getMobType()) > 0) {
            ReplayRecorder.get().markPendingHit(target.getUUID(), ActorFrame.FLAG_MAGIC_CRIT);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer victim) {
            ReplayRecorder.get().confirmHit(victim.getUUID());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof Level level)) return;
        // neighbours too, so the other half of beds/doors and attached torches are captured
        markWithNeighbours(level, event.getPos());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof Level level)) return;
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot snapshot : multi.getReplacedBlockSnapshots()) {
                ReplayRecorder.get().markBlock(level, snapshot.getPos(), snapshot.getReplacedBlock());
            }
        } else {
            ReplayRecorder.get().markBlock(level, event.getPos(), event.getBlockSnapshot().getReplacedBlock());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        for (BlockPos pos : event.getAffectedBlocks()) {
            ReplayRecorder.get().markBlock(level, pos, level.getBlockState(pos));
        }
    }

    private static void markWithNeighbours(Level level, BlockPos pos) {
        ReplayRecorder recorder = ReplayRecorder.get();
        if (!recorder.isRecording()) return;
        recorder.markBlock(level, pos, level.getBlockState(pos));
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            recorder.markBlock(level, neighbour, level.getBlockState(neighbour));
        }
    }

    private ReplayEvents() {}
}
