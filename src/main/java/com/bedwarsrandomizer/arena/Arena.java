package com.bedwarsrandomizer.arena;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.block.teambed.TeamBedBlock;
import com.bedwarsrandomizer.drop.ModTags;
import com.bedwarsrandomizer.fireball.BwrFireball;
import com.bedwarsrandomizer.game.RefillArea;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import java.util.Locale;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;
import com.bedwarsrandomizer.game.GameSettings;

/** The void arena dimension ({@code bedwarsrandomizer:arena}) and its map, pasted from a WorldEdit schematic. */
public final class Arena {
    public static final ResourceKey<Level> LEVEL =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(BedwarsRandomizer.MOD_ID, "arena"));
    /** Where the map was copied from: pasting here works like standing at 0 100 0 and running //paste. */
    public static final BlockPos PASTE_ORIGIN = new BlockPos(0, 100, 0);
    /**
     * Maps inside the mod: {@code src/main/resources/data/bedwarsrandomizer/arena/maps/<name>.schem}, WorldEdit
     * schematics copied while standing at 0 100 0. The file name (spaces and capitals allowed) is the map's name.
     * They are read straight from the mod file, since Minecraft's data loading rejects such names.
     */
    private static final String[] BUNDLED_MAPS_DIR = {"data", BedwarsRandomizer.MOD_ID, "arena", "maps"};
    /** Extra maps without rebuilding the mod: {@code config/bedwarsrandomizer/maps/<name>.schem}. */
    private static final Path MAPS_DIR = FMLPaths.CONFIGDIR.get().resolve(BedwarsRandomizer.MOD_ID).resolve("maps");
    private static final String SCHEMATIC = ".schem";

    /** Every map that can be chosen, by name: the mod's maps, then the config folder's. */
    public static List<String> availableMaps(MinecraftServer server) {
        List<String> maps = new ArrayList<>(listMaps(bundledMapsDir()).keySet());
        listMaps(MAPS_DIR).keySet().stream().filter(name -> !maps.contains(name)).forEach(maps::add);
        return maps;
    }

    /** The map used when the saved one no longer exists: the first in the list. */
    @Nullable
    public static String defaultMap(MinecraftServer server) {
        List<String> maps = availableMaps(server);
        return maps.isEmpty() ? null : maps.get(0);
    }

    @Nullable
    private static Path bundledMapsDir() {
        Path dir = ModList.get().getModFileById(BedwarsRandomizer.MOD_ID).getFile().findResource(BUNDLED_MAPS_DIR);
        return Files.isDirectory(dir) ? dir : null;
    }

    /** {@code <name>.schem} files in {@code dir}, by name, sorted. */
    private static Map<String, Path> listMaps(@Nullable Path dir) {
        Map<String, Path> maps = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (dir == null || !Files.isDirectory(dir)) return maps;
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(file -> {
                String name = file.getFileName().toString();
                if (name.toLowerCase(Locale.ROOT).endsWith(SCHEMATIC) && Files.isRegularFile(file)) {
                    maps.putIfAbsent(name.substring(0, name.length() - SCHEMATIC.length()), file);
                }
            });
        } catch (IOException e) {
            BedwarsRandomizer.LOGGER.error("Could not list maps in {}", dir, e);
        }
        return maps;
    }

    /** Whether someone picked a map since the server started (the round and lobby need that first). */
    private static boolean mapChosen;

    public static boolean isMapChosen() {
        return mapChosen;
    }

    public static void forgetMapChoice() {
        mapChosen = false;
    }

    /** Makes {@code name} the arena map (pasting it now if it isn't the one there). Returns false for unknown maps. */
    public static boolean selectMap(MinecraftServer server, String name) {
        if (!availableMaps(server).contains(name)) return false;
        mapChosen = true;
        GameSettings settings = GameSettings.get();
        if (!name.equals(settings.map)) {
            settings.map = name;
            settings.save();
            cachedBounds = null;
        }
        ensurePasted(server);
        return true;
    }

    @Nullable
    public static ServerLevel level(MinecraftServer server) {
        return server.getLevel(LEVEL);
    }

    public static boolean isArena(LevelAccessor level) {
        return level instanceof Level l && l.dimension() == LEVEL;
    }

    /** Pastes the map the first time the arena exists; later calls do nothing. */
    public static void ensurePasted(MinecraftServer server) {
        ServerLevel level = level(server);
        if (level == null) {
            BedwarsRandomizer.LOGGER.error("Arena dimension {} is missing", LEVEL.location());
            return;
        }
        ArenaData data = ArenaData.get(level);
        try {
            byte[] bytes = readMapBytes(server);
            if (bytes == null) {
                if (!data.isPasted()) BedwarsRandomizer.LOGGER.error("No arena map found ({} in the mod, or {})", String.join("/", BUNDLED_MAPS_DIR), MAPS_DIR);
                return;
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
            if (data.isPasted() && hash.equals(data.mapHash())) return;

            CompoundTag schematic = NbtIo.readCompressed(new ByteArrayInputStream(bytes));
            long start = System.currentTimeMillis();
            BoundingBox bounds = SchematicPaster.bounds(schematic, PASTE_ORIGIN);
            if (data.isPasted()) {
                // another map was chosen or the map file was updated: clear the old map before pasting
                BedwarsRandomizer.LOGGER.info("The arena map changed, replacing it");
                BoundingBox old = data.mapBounds();
                clearArea(level, old != null ? old : bounds.inflatedBy(16));
                data.clearPlayerPlaced();
            }
            int blocks = SchematicPaster.paste(level, PASTE_ORIGIN, schematic);
            data.setPasted(hash, bounds);
            cachedBounds = bounds;
            BedwarsRandomizer.LOGGER.info("Pasted the arena map ({} blocks) in {} ms", blocks, System.currentTimeMillis() - start);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            BedwarsRandomizer.LOGGER.error("Could not paste the arena map", e);
        }
    }

    private static void clearArea(ServerLevel level, BoundingBox box) {
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (!level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
        level.getEntities((Entity) null, AABB.of(box), entity -> !(entity instanceof net.minecraft.world.entity.player.Player))
                .forEach(Entity::discard);
    }

    /** Puts back the map's blocks accepted by {@code filter} (e.g. one kind of randomizer block). Returns how many. */
    public static int restoreBlocks(MinecraftServer server, java.util.function.Predicate<BlockState> filter) {
        ServerLevel level = level(server);
        if (level == null) return 0;
        try {
            CompoundTag map = readMap(server);
            return map == null ? 0 : SchematicPaster.paste(level, PASTE_ORIGIN, map, filter);
        } catch (IOException | RuntimeException e) {
            BedwarsRandomizer.LOGGER.error("Could not restore arena blocks", e);
            return 0;
        }
    }

    @Nullable
    private static byte[] readMapBytes(MinecraftServer server) throws IOException {
        GameSettings settings = GameSettings.get();
        Path file = findMap(settings.map);
        if (file == null) {
            String fallback = defaultMap(server);
            if (fallback == null) return null;
            BedwarsRandomizer.LOGGER.warn("Map {} not found, using {}", settings.map, fallback);
            settings.map = fallback;
            file = findMap(fallback);
            if (file == null) return null;
        }
        return Files.readAllBytes(file);
    }

    /** The file of the map called {@code name}: the mod's first, then the config folder's. */
    @Nullable
    private static Path findMap(String name) {
        Path file = listMaps(bundledMapsDir()).get(name);
        return file != null ? file : listMaps(MAPS_DIR).get(name);
    }

    @Nullable
    private static CompoundTag readMap(MinecraftServer server) throws IOException {
        byte[] bytes = readMapBytes(server);
        return bytes == null ? null : NbtIo.readCompressed(new ByteArrayInputStream(bytes));
    }

    /** The lobby: the arena spawn at 0 100 0. */
    public static void teleport(ServerPlayer player, ServerLevel arena) {
        player.teleportTo(arena, PASTE_ORIGIN.getX() + 0.5, PASTE_ORIGIN.getY(), PASTE_ORIGIN.getZ() + 0.5, player.getYRot(), player.getXRot());
    }

    /** Into the arena lobby in survival, with clear weather. */
    public static boolean teleportToLobby(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        ServerLevel arena = level(server);
        if (arena == null) return false;
        ensurePasted(server);
        teleport(player, arena);
        player.setGameMode(GameType.SURVIVAL);
        applyWorldRules(server);
        return true;
    }

    /** Always clear weather (the arena's dimension type already keeps it day). */
    public static void applyWorldRules(MinecraftServer server) {
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        server.overworld().setWeatherParameters(12000, 0, false, false);
    }

    /**
     * Before a game: removes every player-placed block, puts back every bed and randomizer block from the map, and
     * clears dropped items, TNT, fireballs and leftover refill texts.
     */
    public static void resetMap(MinecraftServer server) {
        ServerLevel level = level(server);
        if (level == null) return;
        ArenaData data = ArenaData.get(level);
        for (long packed : data.playerPlacedPositions()) {
            level.setBlock(BlockPos.of(packed), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        data.clearPlayerPlaced();
        try {
            CompoundTag map = readMap(server);
            if (map != null) {
                SchematicPaster.paste(level, PASTE_ORIGIN, map, state -> state.getBlock() instanceof BedBlock || state.is(ModTags.RANDOM_DROP_BLOCKS));
            }
        } catch (IOException | RuntimeException e) {
            BedwarsRandomizer.LOGGER.error("Could not restore the arena map", e);
        }
        AABB area = AABB.of(mapBounds(server)).inflate(64);
        level.getEntities((Entity) null, area, entity -> entity instanceof ItemEntity || entity instanceof PrimedTnt
                        || entity instanceof BwrFireball || entity.getTags().contains(RefillArea.DISPLAY_TAG))
                .forEach(Entity::discard);
    }

    /** The head of every team bed in the map, by color. */
    public static Map<DyeColor, BlockPos> findTeamBeds(MinecraftServer server) {
        Map<DyeColor, BlockPos> beds = new EnumMap<>(DyeColor.class);
        ServerLevel level = level(server);
        if (level == null) return beds;
        BoundingBox bounds = mapBounds(server);
        for (int chunkX = bounds.minX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
            for (int chunkZ = bounds.minZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
                for (BlockEntity blockEntity : level.getChunk(chunkX, chunkZ).getBlockEntities().values()) {
                    BlockState state = blockEntity.getBlockState();
                    if (state.getBlock() instanceof TeamBedBlock bed && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                        beds.putIfAbsent(bed.getColor(), blockEntity.getBlockPos().immutable());
                    }
                }
            }
        }
        return beds;
    }

    private static BoundingBox cachedBounds;

    /** The blocks the map occupies once pasted. */
    public static BoundingBox mapBounds(MinecraftServer server) {
        if (cachedBounds == null) {
            try {
                CompoundTag map = readMap(server);
                if (map != null) cachedBounds = SchematicPaster.bounds(map, PASTE_ORIGIN);
            } catch (IOException | RuntimeException e) {
                BedwarsRandomizer.LOGGER.error("Could not read the arena map size", e);
            }
            if (cachedBounds == null) cachedBounds = new BoundingBox(-128, 0, -128, 128, 256, 128);
        }
        return cachedBounds;
    }

    private Arena() {}
}
