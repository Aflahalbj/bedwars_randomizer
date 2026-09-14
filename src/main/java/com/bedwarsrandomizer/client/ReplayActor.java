package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.replay.ActorFrame;
import com.bedwarsrandomizer.replay.ReplayData;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;

/**
 * A player puppet that behaves exactly like another player on a normal client: it receives the recorded data the
 * way the server would send it and is ticked by vanilla {@link RemotePlayer} code (position/head interpolation,
 * body turning, walk, swing, hurt and death animations). It is never added to the level; {@link ReplayPlayback}
 * ticks and renders it, so it can share the UUID (and therefore the skin) of the real player.
 */
public class ReplayActor extends RemotePlayer {
    private static final Pose[] POSES = Pose.values();
    /** Vanilla swing length without haste or mining fatigue. */
    private static final int SWING_TICKS = 6;

    private int equipmentIndex = -1;

    public ReplayActor(ClientLevel level, GameProfile profile) {
        super(level, profile);
        this.swingingArm = InteractionHand.MAIN_HAND;
        this.getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7F); // show all skin layers
    }

    /** Places the puppet on a frame without interpolation, like a player coming into view. */
    public void snapTo(ActorFrame frame) {
        moveTo(frame.x(), frame.y(), frame.z(), frame.yRot(), frame.xRot());
        yHeadRot = yHeadRotO = yBodyRot = yBodyRotO = frame.yRot();
        setOldPosAndRot();
    }

    /**
     * Delivers one recorded tick and ticks the puppet once.
     *
     * @param positionUpdate whether the server would have sent a movement packet this tick (every 2 ticks for
     *                       players, and immediately after knockback)
     * @param startSwing     whether to start an arm swing now
     */
    public void receive(ActorFrame frame, boolean hurt, boolean positionUpdate, boolean startSwing, boolean offhandSwing,
                        ReplayData.Actor actor) {
        if (positionUpdate) {
            lerpTo(frame.x(), frame.y(), frame.z(), frame.yRot(), frame.xRot(), 3, false);
            lerpHeadTo(frame.yRot(), 3);
        }

        setOnGround(frame.hasFlag(ActorFrame.FLAG_ON_GROUND));
        setSprinting(frame.hasFlag(ActorFrame.FLAG_SPRINTING));
        setShiftKeyDown(frame.hasFlag(ActorFrame.FLAG_CROUCHING));
        setSwimming(frame.hasFlag(ActorFrame.FLAG_SWIMMING));
        setSharedFlag(FLAG_FALL_FLYING, frame.hasFlag(ActorFrame.FLAG_FALL_FLYING));
        setInvisible(frame.hasFlag(ActorFrame.FLAG_INVISIBLE));
        Pose pose = frame.pose() >= 0 && frame.pose() < POSES.length ? POSES[frame.pose()] : Pose.STANDING;
        if (getPose() != pose) setPose(pose);
        setHealth(frame.health()); // at 0 vanilla plays the death animation

        int index = frame.equipment();
        if (index != equipmentIndex && index >= 0 && index < actor.equipment.size()) {
            ItemStack[] set = actor.equipment.get(index);
            for (int i = 0; i < ReplayData.SLOTS.length; i++) {
                setItemSlot(ReplayData.SLOTS[i], set[i].copy());
            }
            equipmentIndex = index;
        }

        // Spam-clicking restarts a swing halfway, snapping the arm back within one tick; in slow motion that snap
        // still looks like full-speed movement. Let every swing finish before starting the next one.
        if (startSwing && (!swinging || swingTime >= SWING_TICKS - 1)) {
            swing(offhandSwing ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        }
        if (hurt) {
            animateHurt(0);
        }

        // same order as ClientLevel#tickNonPassenger
        setOldPosAndRot();
        tickCount++;
        tick();
    }

    @Override
    protected void pushEntities() {
        // must never shove the real players standing near the replay
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void onBelowWorld() {
        // void kills: keep falling instead of being removed
    }

    @Override
    public boolean isSpectator() {
        return false; // the real player may be spectating by now; the puppet must still render fully
    }

    @Override
    public boolean isCreative() {
        return false;
    }
}
