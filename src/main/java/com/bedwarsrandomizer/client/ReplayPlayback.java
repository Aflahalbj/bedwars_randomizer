package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.replay.ActorFrame;
import com.bedwarsrandomizer.replay.BlockChange;
import com.bedwarsrandomizer.replay.ReplayData;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Plays one {@link ReplayData} on the client as a series of takes: the kill is shown, the victim dies,
 * then the replay rewinds and plays again from another side, slower each time.
 */
public final class ReplayPlayback {
    enum TakeType { WIDE, CRANE, ORBIT }

    record Take(TakeType type, int startFrame, int endFrame, double holdSeconds) {}

    /** A replayed entity: a player puppet or a copy of a projectile / TNT. Never added to the level. */
    private static final class Puppet {
        final Entity entity;
        boolean present;
        int equipment = -1;
        int dataSet = -1;

        Puppet(Entity entity) {
            this.entity = entity;
        }
    }

    private static final int BLOCK_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final double CUT_FADE_SECONDS = 0.15;
    /** Frames replayed silently before each rewound take so interpolation and animations are already running. */
    private static final int PREROLL_FRAMES = 8;
    /**
     * Server recordings only: ticks an arm swing is started before the recorded hit. A swing lasts 6 ticks and the
     * arm is furthest forward halfway, so starting 3 ticks early puts the blow exactly on the damage.
     */
    private static final int SWING_LEAD = 3;
    private static final double OUTRO_SECONDS = 0.7;

    private final Minecraft mc;
    private final ClientLevel level;
    final ReplayData data;

    private final Map<ReplayData.Actor, Puppet> puppets = new LinkedHashMap<>();
    private final Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
    private final List<Take> takes = new ArrayList<>();

    private Marker camera;
    private ReplayDirector director;
    private ReplayDirector.CameraPose pose;
    private CameraType savedCameraType;
    private float savedRain;
    private float savedThunder;
    private boolean addedNightVision;

    private int takeIndex;
    private double takeRealStart;
    private double holdStart = -1;
    private double replayTime;
    private int appliedFrame = -1;
    private int nextBlockChange;
    private float partial;
    private double realTime;
    private long lastNanos;
    private double nextHeartbeat;
    private float flash;
    /** Camera shake amount in [0, 1]; shake strength is trauma squared. */
    private float trauma;
    private float shakeRoll;
    private boolean finished;

    ReplayPlayback(Minecraft mc, ClientLevel level, ReplayData data) {
        this.mc = mc;
        this.level = level;
        this.data = data;

        int last = Math.max(0, data.frameCount - 1);
        int death = Mth.clamp(data.deathFrame, 0, last);
        int afterDeath = Math.min(last, death + 10);
        // the whole clip once, then only the final moments again from other angles
        takes.add(new Take(TakeType.WIDE, 0, afterDeath, 0.5));
        takes.add(new Take(TakeType.CRANE, Math.max(0, death - 20), afterDeath, 0.5));
        takes.add(new Take(TakeType.ORBIT, Math.max(0, death - 12), last, 1.8));
    }

    ClientLevel level() {
        return level;
    }

    void start() {
        for (ReplayData.Actor actor : data.actors) {
            Puppet puppet = createPuppet(actor);
            if (puppet != null) puppets.put(actor, puppet);
        }
        rewindBlocks();

        camera = new Marker(EntityType.MARKER, level);
        director = new ReplayDirector(data, level, camera);

        syncFrames();
        pose = director.update(take(), replayTime, 0, 0);
        applyCamera();

        savedCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON); // the camera entity is the "eye"; no player model offset
        mc.setCameraEntity(camera);

        // readability: no rain streaks in front of the lens, and the fight lit even at night
        savedRain = level.getRainLevel(1.0F);
        savedThunder = level.getThunderLevel(1.0F);
        level.setRainLevel(0);
        level.setThunderLevel(0);
        if (mc.player != null && !mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
            mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 60 * 10, 0, false, false));
            addedNightVision = true;
        }
        lastNanos = System.nanoTime();
    }

    @Nullable
    private Puppet createPuppet(ReplayData.Actor actor) {
        Entity entity = actor.isPlayer()
                ? new ReplayActor(level, new GameProfile(actor.id, actor.name))
                : actor.type.create(level);
        return entity == null ? null : new Puppet(entity);
    }

    /** Called once per rendered frame, before the world is drawn. */
    void update() {
        long now = System.nanoTime();
        double dt = mc.isPaused() ? 0 : Math.min((now - lastNanos) / 1.0E9, 0.1);
        lastNanos = now;
        realTime += dt;

        Take take = take();
        if (holdStart < 0) {
            replayTime = Math.min(take.endFrame(), replayTime + dt * 20.0 * speed());
            if (replayTime >= take.endFrame()) holdStart = realTime;
        }
        syncFrames();

        flash = Math.max(0, flash - (float) dt * 2.0F);
        trauma = Math.max(0, trauma - (float) dt * 1.2F);
        heartbeat();

        pose = director.update(take, replayTime, realTime - takeRealStart, dt);
        applyCamera();

        if (holdStart >= 0 && realTime - holdStart >= take.holdSeconds()) {
            if (takeIndex + 1 < takes.size()) {
                nextTake();
            } else {
                finished = true;
            }
        }
    }

    boolean isFinished() {
        return finished;
    }

    /** Normal end: put the world and camera back. */
    void stop() {
        if (mc.level == level) {
            originalBlocks.forEach((pos, state) -> level.setBlock(pos, state, BLOCK_FLAGS));
            level.setRainLevel(savedRain);
            level.setThunderLevel(savedThunder);
        }
        abort();
    }

    /** The level is gone (disconnect / dimension change): only restore the camera and the player. */
    void abort() {
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
            if (addedNightVision) mc.player.removeEffectNoUpdate(MobEffects.NIGHT_VISION);
        }
        if (savedCameraType != null) mc.options.setCameraType(savedCameraType);
    }

    // ------------------------------------------------------------------------------------------
    // Timeline

    private Take take() {
        return takes.get(takeIndex);
    }

    /** Recorded ticks per real tick for the current take; every take is slower around the kill. */
    private double speed() {
        int death = data.deathFrame;
        return switch (take().type()) {
            case WIDE -> replayTime < death - 6 ? 1.0 : 0.5;
            case CRANE -> replayTime < death + 1 ? 0.35 : 0.5;
            case ORBIT -> replayTime < death + 1 ? 0.18 : 0.4;
        };
    }

    private void nextTake() {
        takeIndex++;
        holdStart = -1;
        takeRealStart = realTime;
        seek(take().startFrame());
    }

    /**
     * Jumps back to {@code frame}: undoes later block changes and rebuilds the puppets, replaying a few frames before
     * the take so interpolation and animations are already in motion when it starts.
     */
    private void seek(int frame) {
        int preroll = Math.max(0, frame - PREROLL_FRAMES);
        List<BlockChange> changes = data.blockChanges;
        while (nextBlockChange > 0 && changes.get(nextBlockChange - 1).frame() >= preroll) {
            BlockChange change = changes.get(--nextBlockChange);
            level.setBlock(change.pos(), change.oldState(), BLOCK_FLAGS);
        }
        puppets.replaceAll((actor, old) -> {
            Puppet fresh = createPuppet(actor);
            return fresh != null ? fresh : old;
        });
        appliedFrame = preroll - 1;
        replayTime = frame;
        syncFrames();
    }

    private void syncFrames() {
        int end = take().endFrame();
        int base = Mth.floor(replayTime);
        int target = Math.min(base + 1, end);
        partial = target == base + 1 ? (float) (replayTime - base) : 1.0F;
        while (appliedFrame < target) {
            applyFrame(++appliedFrame);
        }
    }

    private void applyFrame(int frame) {
        List<BlockChange> changes = data.blockChanges;
        while (nextBlockChange < changes.size() && changes.get(nextBlockChange).frame() <= frame) {
            BlockChange change = changes.get(nextBlockChange++);
            level.setBlock(change.pos(), change.newState(), BLOCK_FLAGS);
        }

        // frames replayed silently before a take (pre-roll) must not make noise
        boolean audible = frame >= Mth.floor(replayTime);
        for (Map.Entry<ReplayData.Actor, Puppet> entry : puppets.entrySet()) {
            ReplayData.Actor actor = entry.getKey();
            Puppet puppet = entry.getValue();
            ActorFrame cur = actor.frames[frame];
            if (cur == null) {
                puppet.present = false;
                continue;
            }
            ActorFrame prev = frame > 0 ? actor.frames[frame - 1] : null;
            boolean hurt = prev != null && cur.hurtTime() > prev.hurtTime();

            if (data.exact) {
                applyExact(actor, puppet, cur, prev);
            } else if (puppet.entity instanceof ReplayActor replayActor) {
                // The server records the swing and the damage in the same tick. Start the arm a little earlier so
                // the blow visibly lands before the victim reacts.
                ActorFrame ahead = frame + SWING_LEAD < actor.frames.length ? actor.frames[frame + SWING_LEAD] : null;
                boolean swing = ahead != null && ahead.hasFlag(ActorFrame.FLAG_SWING);
                boolean offhand = swing && ahead.hasFlag(ActorFrame.FLAG_OFFHAND_SWING);
                if (!puppet.present) replayActor.snapTo(cur);
                // vanilla sends a tracked player's position every 2 ticks, and right away after knockback
                replayActor.receive(cur, hurt, frame % 2 == 0 || hurt, swing, offhand, actor);
            }
            puppet.present = true;

            if (!audible || !actor.isPlayer()) continue;
            if (hurt) {
                level.playLocalSound(cur.x(), cur.y(), cur.z(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS,
                        1.0F, (float) (0.55 + 0.45 * speed()), false);
            }
            if (cur.hasFlag(ActorFrame.FLAG_CRIT)) {
                hitBurst(cur, ParticleTypes.CRIT);
                level.playLocalSound(cur.x(), cur.y(), cur.z(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS,
                        1.0F, (float) (0.6 + 0.4 * speed()), false);
            }
            if (cur.hasFlag(ActorFrame.FLAG_MAGIC_CRIT)) {
                hitBurst(cur, ParticleTypes.ENCHANTED_HIT);
            }
        }

        if (frame == data.deathFrame) {
            onKillMoment();
        }
    }

    /** Client recordings: put the entity in exactly the state the recording client rendered this tick. */
    private void applyExact(ReplayData.Actor actor, Puppet puppet, ActorFrame cur, @Nullable ActorFrame prev) {
        Entity entity = puppet.entity;
        boolean first = !puppet.present || prev == null;
        if (first) {
            entity.moveTo(cur.x(), cur.y(), cur.z(), cur.yRot(), cur.xRot());
        } else {
            entity.setOldPosAndRot();
            entity.setPos(cur.x(), cur.y(), cur.z());
            entity.setYRot(cur.yRot());
            entity.setXRot(cur.xRot());
        }
        entity.tickCount++;
        entity.setOnGround(cur.hasFlag(ActorFrame.FLAG_ON_GROUND));

        if (cur.dataSet() >= 0 && cur.dataSet() != puppet.dataSet && cur.dataSet() < actor.dataSets.size()) {
            entity.getEntityData().assignValues(decodeData(actor.dataSets.get(cur.dataSet())));
            puppet.dataSet = cur.dataSet();
        }

        if (!(entity instanceof LivingEntity living)) return;
        living.yBodyRotO = first ? cur.bodyRot() : living.yBodyRot;
        living.yBodyRot = cur.bodyRot();
        living.yHeadRotO = first ? cur.headRot() : living.yHeadRot;
        living.yHeadRot = cur.headRot();
        living.oAttackAnim = first ? cur.attackAnim() : living.attackAnim;
        living.attackAnim = cur.attackAnim();
        living.swingingArm = cur.hasFlag(ActorFrame.FLAG_OFFHAND_SWING) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        living.hurtTime = cur.hurtTime();
        living.deathTime = cur.deathTime();
        setWalkAnimation(living.walkAnimation, first ? cur.walkSpeed() : prev.walkSpeed(), cur.walkSpeed(), cur.walkPosition());

        if (cur.equipment() >= 0 && cur.equipment() != puppet.equipment && cur.equipment() < actor.equipment.size()) {
            ItemStack[] set = actor.equipment.get(cur.equipment());
            for (int i = 0; i < ReplayData.SLOTS.length; i++) {
                living.setItemSlot(ReplayData.SLOTS[i], set[i].copy());
            }
            puppet.equipment = cur.equipment();
        }
    }

    /** WalkAnimationState has no position setter; drive it with update() so position(partial) matches the recording. */
    private static void setWalkAnimation(WalkAnimationState walk, float previousSpeed, float speed, float position) {
        float start = position - speed;
        float delta = start - walk.position();
        walk.setSpeed(delta);
        walk.update(delta, 0.0F);      // position = start
        walk.setSpeed(previousSpeed);
        walk.update(speed, 1.0F);      // previous speed -> speed, position = start + speed
    }

    private static List<SynchedEntityData.DataValue<?>> decodeData(byte[] bytes) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
        int id;
        while ((id = buf.readUnsignedByte()) != 255) {
            values.add(SynchedEntityData.DataValue.read(buf, id));
        }
        return values;
    }

    /** Puts every recorded block back to how it was at the first frame; remembers the current state to restore. */
    private void rewindBlocks() {
        List<BlockChange> changes = data.blockChanges;
        for (int i = changes.size() - 1; i >= 0; i--) {
            BlockChange change = changes.get(i);
            originalBlocks.putIfAbsent(change.pos(), level.getBlockState(change.pos()));
            level.setBlock(change.pos(), change.oldState(), BLOCK_FLAGS);
        }
    }

    private void onKillMoment() {
        flash = 0.6F;
        trauma = 0.8F;
        ReplayData.Actor victim = data.actor(data.victimId);
        ActorFrame v = victim == null ? null : victim.nearest(data.deathFrame);
        if (v == null) return;

        float pitch = (float) (0.5 + 0.4 * speed());
        level.playLocalSound(v.x(), v.y(), v.z(), SoundEvents.PLAYER_DEATH, SoundSource.PLAYERS, 1.0F, pitch, false);
        if (take().type() == TakeType.ORBIT) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.8F, 0.3F));
        }
    }

    /**
     * Critical / enchanted hit sparks around the victim, like vanilla shows them. Particles can't be slowed down,
     * but slower launch speeds keep them in step with the slow motion.
     */
    private void hitBurst(ActorFrame victim, ParticleOptions particle) {
        RandomSource random = level.random;
        double slow = Math.max(0.15, speed());
        for (int i = 0; i < 24; i++) {
            double ox = (random.nextDouble() - 0.5) * 0.6;
            double oy = random.nextDouble() * 1.8;
            double oz = (random.nextDouble() - 0.5) * 0.6;
            level.addParticle(particle, victim.x() + ox, victim.y() + oy, victim.z() + oz,
                    ox * 2 * slow, (random.nextDouble() * 0.4 + 0.1) * slow, oz * 2 * slow);
        }
    }

    private void heartbeat() {
        boolean slowApproach = take().type() == TakeType.ORBIT && holdStart < 0 && replayTime < data.deathFrame;
        if (slowApproach && realTime >= nextHeartbeat) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.WARDEN_HEARTBEAT, 1.0F, 1.0F));
            nextHeartbeat = realTime + 0.75;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Camera & rendering

    /** Positions the camera entity; shake is rotation only, from smooth noise, scaled by trauma squared. */
    private void applyCamera() {
        Vec3 pos = pose.pos();
        float shake = trauma * trauma;
        float yaw = pose.yaw() + 3.0F * shake * noise(1.3, realTime);
        float pitch = pose.pitch() + 3.0F * shake * noise(7.1, realTime);
        shakeRoll = 2.0F * shake * noise(13.7, realTime);

        camera.setPos(pos.x, pos.y, pos.z);
        camera.xo = camera.xOld = pos.x;
        camera.yo = camera.yOld = pos.y;
        camera.zo = camera.zOld = pos.z;
        camera.setYRot(yaw);
        camera.yRotO = yaw;
        camera.setXRot(pitch);
        camera.xRotO = pitch;
    }

    /** Smooth pseudo-noise in [-1, 1]; different seeds give independent channels. */
    private static float noise(double seed, double time) {
        return (float) ((Math.sin(time * 13.1 + seed) + 0.6 * Math.sin(time * 23.7 + seed * 1.7)
                + 0.3 * Math.sin(time * 37.3 + seed * 2.3)) / 1.9);
    }

    void renderActors(PoseStack poseStack, Camera cam) {
        Vec3 camPos = cam.getPosition();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        for (Puppet puppet : puppets.values()) {
            if (!puppet.present) continue;
            Entity entity = puppet.entity;
            double x = Mth.lerp(partial, entity.xo, entity.getX());
            double y = Mth.lerp(partial, entity.yo, entity.getY());
            double z = Mth.lerp(partial, entity.zo, entity.getZ());
            float yaw = Mth.lerp(partial, entity.yRotO, entity.getYRot());
            int light = dispatcher.getPackedLightCoords(entity, partial);
            dispatcher.render(entity, x - camPos.x, y - camPos.y, z - camPos.z, yaw, partial, poseStack, buffers, light);
        }
        buffers.endBatch();
    }

    float roll() {
        return pose.roll() + shakeRoll;
    }

    float fov() {
        return pose.fov();
    }

    // ------------------------------------------------------------------------------------------
    // Values for the overlay and the test harness

    double realTime() {
        return realTime;
    }

    double replayTime() {
        return replayTime;
    }

    int takeIndex() {
        return takeIndex;
    }

    float partial() {
        return partial;
    }

    /** The puppet playing the player with {@code id}, or null. */
    @Nullable
    LivingEntity puppet(UUID id) {
        for (Map.Entry<ReplayData.Actor, Puppet> entry : puppets.entrySet()) {
            if (entry.getKey().id.equals(id) && entry.getValue().entity instanceof LivingEntity living) return living;
        }
        return null;
    }

    ReplayDirector.CameraPose pose() {
        return pose;
    }

    float flash() {
        return flash;
    }

    /** Black overlay alpha: hides the first camera switch, dips between takes and fades out at the end. */
    float blackness() {
        float intro = realTime < 0.25 ? 1 : (float) Math.max(0, 1 - (realTime - 0.25) / 0.5);
        float takeIn = takeIndex == 0 ? 0 : (float) Math.max(0, 1 - (realTime - takeRealStart) / 0.25);
        float takeOut = 0;
        if (holdStart >= 0) {
            double remaining = take().holdSeconds() - (realTime - holdStart);
            double fade = takeIndex + 1 < takes.size() ? CUT_FADE_SECONDS : OUTRO_SECONDS;
            takeOut = (float) Mth.clamp(1 - remaining / fade, 0, 1);
        }
        return Math.max(intro, Math.max(takeIn, takeOut));
    }
}
