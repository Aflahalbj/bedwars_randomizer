package com.bedwarsrandomizer.replay;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/**
 * One recorded tick of one entity.
 *
 * <p>Client recordings ({@link ReplayData#exact}) store exactly what that client rendered: position, look, body and
 * head rotation, arm swing, walk animation, hurt/death time and the synced entity data ({@code dataSet}). Server
 * recordings only know position, look, flags and events; the replay then rebuilds the animation with vanilla code.
 */
public record ActorFrame(
        double x, double y, double z,
        float yRot, float xRot,
        float bodyRot, float headRot,
        float attackAnim, float walkPosition, float walkSpeed,
        byte pose, int flags,
        byte hurtTime, byte deathTime,
        float health, float maxHealth,
        int equipment, int dataSet) {

    public static final int FLAG_SPRINTING = 1;
    public static final int FLAG_FALL_FLYING = 2;
    public static final int FLAG_INVISIBLE = 4;
    public static final int FLAG_ON_GROUND = 8;
    public static final int FLAG_OFFHAND_SWING = 16;
    /** The player started swinging an arm this tick. */
    public static final int FLAG_SWING = 32;
    public static final int FLAG_CROUCHING = 64;
    public static final int FLAG_SWIMMING = 128;
    /** The player took a critical hit this tick. */
    public static final int FLAG_CRIT = 256;
    /** The player took a hit with an enchanted weapon (Sharpness etc.) this tick. */
    public static final int FLAG_MAGIC_CRIT = 512;

    /** Flags that describe a one-tick event rather than a state. */
    private static final int EVENT_FLAGS = FLAG_SWING | FLAG_CRIT | FLAG_MAGIC_CRIT;

    /** A frame without exact animation state (server recordings, scripted tests). */
    public static ActorFrame basic(double x, double y, double z, float yRot, float xRot, byte pose, int flags,
                                   byte hurtTime, byte deathTime, float health, float maxHealth, int equipment) {
        return new ActorFrame(x, y, z, yRot, xRot, yRot, yRot, 0, 0, 0, pose, flags, hurtTime, deathTime,
                health, maxHealth, equipment, -1);
    }

    /**
     * @param hitFlags {@link #FLAG_CRIT} / {@link #FLAG_MAGIC_CRIT} for hits that landed on this player this tick
     */
    public static ActorFrame capture(ServerPlayer p, boolean swingStarted, int hitFlags) {
        int flags = hitFlags;
        if (p.isSprinting()) flags |= FLAG_SPRINTING;
        if (p.isFallFlying()) flags |= FLAG_FALL_FLYING;
        if (p.isInvisible()) flags |= FLAG_INVISIBLE;
        if (p.onGround()) flags |= FLAG_ON_GROUND;
        if (p.swingingArm == InteractionHand.OFF_HAND) flags |= FLAG_OFFHAND_SWING;
        if (swingStarted) flags |= FLAG_SWING;
        if (p.isShiftKeyDown()) flags |= FLAG_CROUCHING;
        if (p.isSwimming()) flags |= FLAG_SWIMMING;

        return basic(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(),
                (byte) p.getPose().ordinal(), flags,
                (byte) Math.min(p.hurtTime, 127), (byte) Math.min(p.deathTime, 127),
                p.getHealth(), p.getMaxHealth(), -1);
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public ActorFrame withEquipment(int index) {
        return withIndices(index, dataSet);
    }

    public ActorFrame withIndices(int equipmentIndex, int dataSetIndex) {
        return new ActorFrame(x, y, z, yRot, xRot, bodyRot, headRot, attackAnim, walkPosition, walkSpeed, pose, flags,
                hurtTime, deathTime, health, maxHealth, equipmentIndex, dataSetIndex);
    }

    public ActorFrame withFlags(int newFlags) {
        return new ActorFrame(x, y, z, yRot, xRot, bodyRot, headRot, attackAnim, walkPosition, walkSpeed, pose, newFlags,
                hurtTime, deathTime, health, maxHealth, equipment, dataSet);
    }

    public ActorFrame asDead(int deathTicks) {
        return new ActorFrame(x, y, z, yRot, xRot, bodyRot, headRot, 0, walkPosition, 0, pose, flags & ~EVENT_FLAGS,
                (byte) 0, (byte) Math.min(deathTicks, 20), 0F, maxHealth, equipment, dataSet);
    }

    /** This frame with the one-tick events (swing, critical hits) and hurt time of {@code other}. */
    public ActorFrame withEventsOf(ActorFrame other) {
        return new ActorFrame(x, y, z, yRot, xRot, bodyRot, headRot, attackAnim, walkPosition, walkSpeed, pose,
                (flags & ~EVENT_FLAGS) | (other.flags & EVENT_FLAGS), other.hurtTime, deathTime, health, maxHealth,
                equipment, dataSet);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(yRot);
        buf.writeFloat(xRot);
        buf.writeFloat(bodyRot);
        buf.writeFloat(headRot);
        buf.writeFloat(attackAnim);
        buf.writeFloat(walkPosition);
        buf.writeFloat(walkSpeed);
        buf.writeByte(pose);
        buf.writeVarInt(flags);
        buf.writeByte(hurtTime);
        buf.writeByte(deathTime);
        buf.writeFloat(health);
        buf.writeFloat(maxHealth);
        buf.writeVarInt(equipment + 1);
        buf.writeVarInt(dataSet + 1);
    }

    public static ActorFrame read(FriendlyByteBuf buf) {
        return new ActorFrame(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readByte(), buf.readVarInt(),
                buf.readByte(), buf.readByte(),
                buf.readFloat(), buf.readFloat(),
                buf.readVarInt() - 1, buf.readVarInt() - 1);
    }
}
