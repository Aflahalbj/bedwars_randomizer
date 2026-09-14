package com.bedwarsrandomizer.replay;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A finished kill recording. Frames are indexed from 0 (oldest) to {@code frameCount - 1}. */
public final class ReplayData {
    /** Equipment slots in the order they are stored. */
    public static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    public final UUID replayId;
    public final long createdAt;
    public final UUID killerId;
    public final String killerName;
    public final UUID victimId;
    public final String victimName;
    public final ResourceKey<Level> dimension;
    public final KillCause cause;
    public final ItemStack weapon;
    public final int frameCount;
    public final int deathFrame;
    /** Players and other recorded entities (projectiles, TNT). */
    public final List<Actor> actors;
    public final List<BlockChange> blockChanges;
    /**
     * True when recorded on a client: frames hold exactly what that player saw and are replayed as-is.
     * False for the server's own recording, which the replay animates with vanilla client code.
     */
    public final boolean exact;

    public ReplayData(UUID replayId, long createdAt, UUID killerId, String killerName, UUID victimId, String victimName,
                      ResourceKey<Level> dimension, KillCause cause, ItemStack weapon, int frameCount, int deathFrame,
                      List<Actor> actors, List<BlockChange> blockChanges, boolean exact) {
        this.replayId = replayId;
        this.createdAt = createdAt;
        this.killerId = killerId;
        this.killerName = killerName;
        this.victimId = victimId;
        this.victimName = victimName;
        this.dimension = dimension;
        this.cause = cause;
        this.weapon = weapon;
        this.frameCount = frameCount;
        this.deathFrame = deathFrame;
        this.actors = actors;
        this.blockChanges = blockChanges;
        this.exact = exact;
    }

    @Nullable
    public Actor actor(UUID id) {
        for (Actor actor : actors) {
            if (actor.id.equals(id)) return actor;
        }
        return null;
    }

    /** Copy containing only the killer and victim, used when the full replay is too big for one packet. */
    public ReplayData onlyKillerAndVictim() {
        List<Actor> kept = new ArrayList<>();
        for (Actor actor : actors) {
            if (actor.id.equals(killerId) || actor.id.equals(victimId)) kept.add(actor);
        }
        return new ReplayData(replayId, createdAt, killerId, killerName, victimId, victimName, dimension, cause,
                weapon, frameCount, deathFrame, kept, blockChanges, exact);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(replayId);
        buf.writeLong(createdAt);
        buf.writeUUID(killerId);
        buf.writeUtf(killerName);
        buf.writeUUID(victimId);
        buf.writeUtf(victimName);
        buf.writeResourceKey(dimension);
        buf.writeEnum(cause);
        buf.writeItem(weapon);
        buf.writeVarInt(frameCount);
        buf.writeVarInt(deathFrame);
        buf.writeBoolean(exact);

        buf.writeVarInt(actors.size());
        for (Actor actor : actors) {
            actor.write(buf);
        }
        buf.writeVarInt(blockChanges.size());
        for (BlockChange change : blockChanges) {
            change.write(buf);
        }
    }

    public static ReplayData read(FriendlyByteBuf buf) {
        UUID replayId = buf.readUUID();
        long createdAt = buf.readLong();
        UUID killerId = buf.readUUID();
        String killerName = buf.readUtf();
        UUID victimId = buf.readUUID();
        String victimName = buf.readUtf();
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        KillCause cause = buf.readEnum(KillCause.class);
        ItemStack weapon = buf.readItem();
        int frameCount = buf.readVarInt();
        int deathFrame = buf.readVarInt();
        boolean exact = buf.readBoolean();

        int actorCount = buf.readVarInt();
        List<Actor> actors = new ArrayList<>(actorCount);
        for (int i = 0; i < actorCount; i++) {
            actors.add(Actor.read(buf, frameCount));
        }
        int changeCount = buf.readVarInt();
        List<BlockChange> changes = new ArrayList<>(changeCount);
        for (int i = 0; i < changeCount; i++) {
            changes.add(BlockChange.read(buf));
        }
        return new ReplayData(replayId, createdAt, killerId, killerName, victimId, victimName, dimension, cause,
                weapon, frameCount, deathFrame, actors, changes, exact);
    }

    /** One recorded entity: a player ({@link #type} is null) or a projectile / TNT. */
    public static final class Actor {
        public final UUID id;
        public final String name;
        @Nullable public final EntityType<?> type;
        /** Distinct equipment sets; frames refer to them by index. Each array follows {@link #SLOTS}. */
        public final List<ItemStack[]> equipment;
        /** Distinct synced entity data snapshots (vanilla entity data packet format); frames refer to them by index. */
        public final List<byte[]> dataSets;
        /** One entry per replay frame, {@code null} when the entity was not present. */
        public final ActorFrame[] frames;

        public Actor(UUID id, String name, List<ItemStack[]> equipment, ActorFrame[] frames) {
            this(id, name, null, equipment, List.of(), frames);
        }

        public Actor(UUID id, String name, @Nullable EntityType<?> type, List<ItemStack[]> equipment,
                     List<byte[]> dataSets, ActorFrame[] frames) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.equipment = equipment;
            this.dataSets = dataSets;
            this.frames = frames;
        }

        public boolean isPlayer() {
            return type == null;
        }

        /** The frame at {@code index}, or the closest present frame if the entity was absent then. */
        @Nullable
        public ActorFrame nearest(int index) {
            index = Math.max(0, Math.min(frames.length - 1, index));
            for (int d = 0; d < frames.length; d++) {
                if (index - d >= 0 && frames[index - d] != null) return frames[index - d];
                if (index + d < frames.length && frames[index + d] != null) return frames[index + d];
            }
            return null;
        }

        void write(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeUtf(name);
            buf.writeBoolean(type != null);
            if (type != null) buf.writeId(BuiltInRegistries.ENTITY_TYPE, type);
            buf.writeVarInt(equipment.size());
            for (ItemStack[] set : equipment) {
                for (ItemStack stack : set) {
                    buf.writeItem(stack);
                }
            }
            buf.writeVarInt(dataSets.size());
            for (byte[] data : dataSets) {
                buf.writeByteArray(data);
            }
            for (ActorFrame frame : frames) {
                buf.writeBoolean(frame != null);
                if (frame != null) frame.write(buf);
            }
        }

        static Actor read(FriendlyByteBuf buf, int frameCount) {
            UUID id = buf.readUUID();
            String name = buf.readUtf();
            EntityType<?> type = buf.readBoolean() ? buf.readById(BuiltInRegistries.ENTITY_TYPE) : null;
            int sets = buf.readVarInt();
            List<ItemStack[]> equipment = new ArrayList<>(sets);
            for (int i = 0; i < sets; i++) {
                ItemStack[] set = new ItemStack[SLOTS.length];
                for (int s = 0; s < SLOTS.length; s++) {
                    set[s] = buf.readItem();
                }
                equipment.add(set);
            }
            int dataCount = buf.readVarInt();
            List<byte[]> dataSets = new ArrayList<>(dataCount);
            for (int i = 0; i < dataCount; i++) {
                dataSets.add(buf.readByteArray());
            }
            ActorFrame[] frames = new ActorFrame[frameCount];
            for (int i = 0; i < frameCount; i++) {
                frames[i] = buf.readBoolean() ? ActorFrame.read(buf) : null;
            }
            return new Actor(id, name, type, equipment, dataSets, frames);
        }
    }
}
