package com.bedwarsrandomizer.fireball;

import com.bedwarsrandomizer.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Fireball;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

import java.util.Optional;

/** The Bedwars fireball thrown with a fire charge: flies straight, explodes on impact, can't break glass. */
public class BwrFireball extends Fireball {
    /** Big blast like Hypixel's (BedWars1058 uses a yield of 3.5); player damage is scaled down in FireballEvents. */
    public static final float EXPLOSION_POWER = 3.0F;
    /**
     * A normal hit ≈ 2 HP (1 heart), dead center at most ≈ 8 HP before armor, like Hypixel's "1-2 hearts, more point
     * blank"; knockback stays full. Vanilla damage of this blast would be up to ~43 HP.
     */
    public static final float DAMAGE_MULTIPLIER = 0.2F;
    private static final double LAUNCH_SPEED = 1.0;

    /** Glass stops the blast completely, so it also shields whatever is behind it. */
    private static final ExplosionDamageCalculator GLASS_PROOF = new ExplosionDamageCalculator() {
        @Override
        public Optional<Float> getBlockExplosionResistance(Explosion explosion, BlockGetter level, BlockPos pos, BlockState state, FluidState fluid) {
            if (isBlastProofGlass(state)) return Optional.of(3_600_000.0F);
            return super.getBlockExplosionResistance(explosion, level, pos, state, fluid);
        }
    };

    public static boolean isBlastProofGlass(BlockState state) {
        return state.is(Tags.Blocks.GLASS) || state.is(Tags.Blocks.GLASS_PANES);
    }

    public BwrFireball(EntityType<? extends BwrFireball> type, Level level) {
        super(type, level);
    }

    public BwrFireball(Level level, LivingEntity owner, Vec3 direction) {
        super(ModEntities.FIREBALL.get(), owner, direction.x, direction.y, direction.z, level);
        Vec3 dir = direction.normalize();
        Vec3 eye = owner.getEyePosition();
        setPos(eye.x + dir.x, eye.y + dir.y - getBbHeight() / 2, eye.z + dir.z);
        setDeltaMovement(dir.scale(LAUNCH_SPEED));
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!level().isClientSide) {
            level().explode(this, damageSources().fireball(this, getOwner()), GLASS_PROOF,
                    getX(), getY(), getZ(), EXPLOSION_POWER, false, Level.ExplosionInteraction.TNT);
            discard();
        }
    }
}
