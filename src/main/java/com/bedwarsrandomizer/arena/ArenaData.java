package com.bedwarsrandomizer.arena;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;

/** Saved with the arena: whether the map was pasted, and which positions hold blocks placed by players. */
public final class ArenaData extends SavedData {
    private static final String NAME = "bedwarsrandomizer_arena";

    private boolean pasted;
    /** SHA-1 of the map file that was pasted; a different file means the map was updated. */
    private String mapHash = "";
    private int[] mapBounds = new int[0];
    private final LongOpenHashSet playerPlaced = new LongOpenHashSet();

    public static ArenaData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(ArenaData::load, ArenaData::new, NAME);
    }

    private static ArenaData load(CompoundTag tag) {
        ArenaData data = new ArenaData();
        data.pasted = tag.getBoolean("pasted");
        data.mapHash = tag.getString("mapHash");
        data.mapBounds = tag.getIntArray("mapBounds");
        for (long pos : tag.getLongArray("playerPlaced")) data.playerPlaced.add(pos);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("pasted", pasted);
        tag.putString("mapHash", mapHash);
        tag.putIntArray("mapBounds", mapBounds);
        tag.putLongArray("playerPlaced", playerPlaced.toLongArray());
        return tag;
    }

    public boolean isPasted() {
        return pasted;
    }

    public String mapHash() {
        return mapHash;
    }

    /** Where the pasted map is, so switching maps can clear exactly it; null for arenas pasted before this was kept. */
    @Nullable
    public BoundingBox mapBounds() {
        return mapBounds.length == 6 ? new BoundingBox(mapBounds[0], mapBounds[1], mapBounds[2], mapBounds[3], mapBounds[4], mapBounds[5]) : null;
    }

    public void setPasted(String mapHash, BoundingBox bounds) {
        pasted = true;
        this.mapHash = mapHash;
        this.mapBounds = new int[]{bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()};
        setDirty();
    }

    public boolean isPlayerPlaced(BlockPos pos) {
        return playerPlaced.contains(pos.asLong());
    }

    public void markPlayerPlaced(BlockPos pos) {
        if (playerPlaced.add(pos.asLong())) setDirty();
    }

    public void unmark(BlockPos pos) {
        if (playerPlaced.remove(pos.asLong())) setDirty();
    }

    public long[] playerPlacedPositions() {
        return playerPlaced.toLongArray();
    }

    public void clearPlayerPlaced() {
        if (playerPlaced.isEmpty()) return;
        playerPlaced.clear();
        setDirty();
    }
}
