package com.bedwarsrandomizer.game;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.arena.Arena;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Bed Wars combat rules: snowball knockback, weaker TNT, no PvP outside a round, harmless victory fireworks. */
@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID)
public final class CombatEvents {
    /**
     * Like Minecraft 1.8 (what Hypixel runs): a snowball hit does no real damage but still knocks back. Newer
     * versions ignore 0-damage hits entirely, so the hit gets a tiny amount instead.
     */
    private static final float SNOWBALL_DAMAGE = 0.01F;
    private static final double SNOWBALL_KNOCKBACK = 0.4;
    /** Hypixel TNT does about 1-3 hearts; vanilla TNT can do over 25. */
    private static final float TNT_DAMAGE_MULTIPLIER = 0.1F;

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof Snowball snowball) || snowball.level().isClientSide) return;
        if (!(event.getRayTraceResult() instanceof EntityHitResult hit) || !(hit.getEntity() instanceof Player target)) return;
        Entity owner = snowball.getOwner();
        if (target == owner) return;
        boolean hurt = target.hurt(snowball.damageSources().thrown(snowball, owner), SNOWBALL_DAMAGE);
        if (hurt && owner == null) {
            // with a thrower, the hit already knocks back away from them; without one, push along the flight
            Vec3 motion = snowball.getDeltaMovement();
            target.knockback(SNOWBALL_KNOCKBACK, -motion.x, -motion.z);
        }
    }

    @SubscribeEvent
    public static void onAttack(LivingAttackEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !Arena.isArena(victim.level())) return;
        DamageSource source = event.getSource();
        if (source.is(DamageTypes.FIREWORKS)) {
            event.setCanceled(true); // the victory fireworks are just for show
            return;
        }
        if (victim instanceof Player && source.getEntity() instanceof Player attacker && attacker != victim
                && BedwarsGame.get().phase() != BedwarsGame.Phase.RUNNING) {
            event.setCanceled(true); // PvP only while a round is running
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (event.getSource().getDirectEntity() instanceof PrimedTnt && Arena.isArena(event.getEntity().level())) {
            event.setAmount(event.getAmount() * TNT_DAMAGE_MULTIPLIER);
        }
    }

    private CombatEvents() {}
}
