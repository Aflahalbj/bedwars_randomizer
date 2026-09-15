package com.bedwarsrandomizer.fireball;

import com.bedwarsrandomizer.BedwarsRandomizer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Right-clicking with a fire charge (in the air or at a block) throws a {@link BwrFireball} instead of lighting fire. */
@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID)
public final class FireballEvents {
    private static final int COOLDOWN_TICKS = 10;

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (throwFireball(event.getEntity(), event.getHand(), event.getLevel())) {
            event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (throwFireball(event.getEntity(), event.getHand(), event.getLevel())) {
            event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
            event.setCanceled(true);
        }
    }

    /** The only damage with a fireball as direct cause is its explosion (it has no impact damage). */
    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (event.getSource().getDirectEntity() instanceof BwrFireball) {
            event.setAmount(event.getAmount() * BwrFireball.DAMAGE_MULTIPLIER);
        }
    }

    /** Returns whether the click was a fire charge (then vanilla must not use it), thrown or still cooling down. */
    private static boolean throwFireball(Player player, InteractionHand hand, Level level) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(Items.FIRE_CHARGE)) return false;
        if (player.getCooldowns().isOnCooldown(Items.FIRE_CHARGE)) return true;

        if (!level.isClientSide) {
            level.addFreshEntity(new BwrFireball(level, player, player.getLookAngle()));
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GHAST_SHOOT, SoundSource.PLAYERS, 0.8F, 1.0F);
        }
        player.getCooldowns().addCooldown(Items.FIRE_CHARGE, COOLDOWN_TICKS);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        return true;
    }

    private FireballEvents() {}
}
