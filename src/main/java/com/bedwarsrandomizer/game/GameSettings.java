package com.bedwarsrandomizer.game;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.drop.RandomizerSource;
import com.google.common.collect.Iterables;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Bed Wars game settings and the registered players (their UUID, name, skin, chosen team or auto, and whether they
 * host), edited with {@code /bwr regis}, {@code /bwr host} and {@code /bwr setting}. Timers, team size and the map are
 * saved to {@code config/bedwarsrandomizer/game.json}; registrations only last until the server stops.
 */
public final class GameSettings {
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve(BedwarsRandomizer.MOD_ID).resolve("game.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int DEFAULT_REFILL_SECONDS = 30;

    /** The "textures" property of a player's profile: what a client needs to show their skin. */
    public record Skin(String value, @Nullable String signature) {}

    /** {@code team} null = assigned automatically. Hosts don't play; they watch as spectators. */
    public record Registered(UUID id, String name, @Nullable DyeColor team, boolean host, @Nullable Skin skin) {}

    private static GameSettings instance;

    public int startCountdown = 5;
    public int respawnSeconds = 5;
    public int spawnDistance = 9;
    public int playersPerTeam = 1;
    /** The arena map (see Arena#availableMaps). */
    public String map = "";
    private final EnumMap<RandomizerSource, Integer> refillSeconds = new EnumMap<>(RandomizerSource.class);
    private final LinkedHashMap<UUID, Registered> players = new LinkedHashMap<>();

    private GameSettings() {
        for (RandomizerSource source : RandomizerSource.values()) refillSeconds.put(source, DEFAULT_REFILL_SECONDS);
    }

    public static synchronized GameSettings get() {
        if (instance == null) instance = load();
        return instance;
    }

    @Nullable
    public static Skin skinOf(GameProfile profile) {
        Property textures = Iterables.getFirst(profile.getProperties().get("textures"), null);
        return textures == null ? null : new Skin(textures.getValue(), textures.getSignature());
    }

    public void setNumbers(int startCountdown, int respawnSeconds, int spawnDistance, int playersPerTeam) {
        this.startCountdown = Mth.clamp(startCountdown, 0, 60);
        this.respawnSeconds = Mth.clamp(respawnSeconds, 0, 60);
        this.spawnDistance = Mth.clamp(spawnDistance, 1, 30);
        this.playersPerTeam = Mth.clamp(playersPerTeam, 1, 16);
    }

    public int refillSeconds(RandomizerSource source) {
        return refillSeconds.get(source);
    }

    public void setRefillSeconds(RandomizerSource source, int seconds) {
        refillSeconds.put(source, Mth.clamp(seconds, 1, 3600));
    }

    public List<Registered> players() {
        return List.copyOf(players.values());
    }

    @Nullable
    public Registered player(UUID id) {
        return players.get(id);
    }

    @Nullable
    public Registered playerByName(String name) {
        return players.values().stream().filter(player -> player.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public boolean isHost(UUID id) {
        Registered player = players.get(id);
        return player != null && player.host();
    }

    @Nullable
    public Skin savedSkin(UUID id) {
        Registered player = players.get(id);
        return player == null ? null : player.skin();
    }

    /** Registers (or refreshes) a player, keeping their team and role. Returns whether they were new. */
    public boolean register(ServerPlayer player) {
        Registered old = players.get(player.getUUID());
        Skin skin = skinOf(player.getGameProfile());
        players.put(player.getUUID(), new Registered(player.getUUID(), player.getGameProfile().getName(),
                old == null ? null : old.team(), old != null && old.host(), skin != null ? skin : old == null ? null : old.skin()));
        return old == null;
    }

    /** Makes an online player a host (registering them) or a normal player again. */
    public void setHost(ServerPlayer player, boolean host) {
        register(player);
        setHost(player.getUUID(), host);
    }

    public boolean setHost(UUID id, boolean host) {
        Registered old = players.get(id);
        if (old == null || old.host() == host) return false;
        players.put(id, new Registered(old.id(), old.name(), old.team(), host, old.skin()));
        return true;
    }

    public int clearHosts() {
        int count = 0;
        for (Registered player : players()) {
            if (setHost(player.id(), false)) count++;
        }
        return count;
    }

    /** A registered player joined: keep their name and skin up to date. Returns whether anything changed. */
    public boolean refresh(ServerPlayer player) {
        Registered old = players.get(player.getUUID());
        if (old == null) return false;
        Skin skin = skinOf(player.getGameProfile());
        String name = player.getGameProfile().getName();
        if (name.equals(old.name()) && (skin == null || skin.equals(old.skin()))) return false;
        players.put(old.id(), new Registered(old.id(), name, old.team(), old.host(), skin != null ? skin : old.skin()));
        return true;
    }

    public boolean unregister(UUID id) {
        return players.remove(id) != null;
    }

    public void unregisterAll() {
        players.clear();
    }

    public void setPlayers(List<Registered> registered) {
        players.clear();
        registered.forEach(player -> players.put(player.id(), player));
    }

    private static GameSettings load() {
        GameSettings settings = new GameSettings();
        if (!Files.exists(FILE)) return settings;
        try (Reader reader = Files.newBufferedReader(FILE)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            settings.setNumbers(GsonHelper.getAsInt(root, "startCountdown", 5), GsonHelper.getAsInt(root, "respawnSeconds", 5),
                    GsonHelper.getAsInt(root, "spawnDistance", 9), GsonHelper.getAsInt(root, "playersPerTeam", 1));
            JsonObject refill = GsonHelper.getAsJsonObject(root, "refillSeconds", new JsonObject());
            for (RandomizerSource source : RandomizerSource.values()) {
                settings.setRefillSeconds(source, GsonHelper.getAsInt(refill, source.key(), DEFAULT_REFILL_SECONDS));
            }
            settings.map = GsonHelper.getAsString(root, "map", "");
        } catch (IOException | RuntimeException e) {
            BedwarsRandomizer.LOGGER.error("Could not read {}, using default game settings", FILE, e);
        }
        return settings;
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("startCountdown", startCountdown);
        root.addProperty("respawnSeconds", respawnSeconds);
        root.addProperty("spawnDistance", spawnDistance);
        root.addProperty("playersPerTeam", playersPerTeam);
        JsonObject refill = new JsonObject();
        refillSeconds.forEach((source, seconds) -> refill.addProperty(source.key(), seconds));
        root.add("refillSeconds", refill);
        root.addProperty("map", map);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root));
        } catch (IOException e) {
            BedwarsRandomizer.LOGGER.error("Could not save {}", FILE, e);
        }
    }
}
