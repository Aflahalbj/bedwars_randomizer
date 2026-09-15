package com.bedwarsrandomizer.arena;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Saved with the arena: whether the map was pasted, and which positions hold blocks placed by players. */
public final class ArenaData extends SavedData {
    private static final String NAME = "bedwarsrandomizer_arena";

    private boolean pasted;
    /** SHA-1 of the map file that was pasted; a different file means the map was updated. */
    private String mapHash = "";
    private final LongOpenHashSet playerPlaced = new LongOpenHashSet();

    public static ArenaData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(ArenaData::load, ArenaData::new, NAME);
    }

    private static ArenaData load(CompoundTag tag) {
        ArenaData data = new ArenaData();
        data.pasted = tag.getBoolean("pasted");
        data.mapHash = tag.getString("mapHash");
        for (long pos : tag.getLongArray("playerPlaced")) data.playerPlaced.add(pos);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("pasted", pasted);
        tag.putString("mapHash", mapHash);
        tag.putLongArray("playerPlaced", playerPlaced.toLongArray());
        return tag;
    }

    public boolean isPasted() {
        return pasted;
    }

    public String mapHash() {
        return mapHash;
    }

    public void setPasted(String mapHash) {
        pasted = true;
        this.mapHash = mapHash;
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
