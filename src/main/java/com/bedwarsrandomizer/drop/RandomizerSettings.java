package com.bedwarsrandomizer.drop;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.drop.DropCandidates.Candidate;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What {@code /bwr setting randomizer} edits, per randomizer block: on/off, category weights and per-item overrides of
 * the default rules. Saved to {@code config/bedwarsrandomizer/randomizer.json}; only values that differ from the
 * defaults are stored, so better defaults in a later version still apply to untouched items.
 */
public final class RandomizerSettings {
    public static final int MAX_WEIGHT = 1000;
    public static final int MAX_COUNT = 64;
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve(BedwarsRandomizer.MOD_ID).resolve("randomizer.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Whether an item can drop, how common it is (relative to its category) and how many drop at once. */
    public record Rule(boolean enabled, int weight, int min, int max) {
        public Rule {
            weight = Mth.clamp(weight, 0, MAX_WEIGHT);
            min = Mth.clamp(min, 1, MAX_COUNT);
            max = Mth.clamp(max, min, MAX_COUNT);
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(enabled);
            buf.writeVarInt(weight);
            buf.writeByte(min);
            buf.writeByte(max);
        }

        public static Rule read(FriendlyByteBuf buf) {
            return new Rule(buf.readBoolean(), buf.readVarInt(), buf.readUnsignedByte(), buf.readUnsignedByte());
        }
    }

    /** Settings of one randomizer block. Overrides are keyed by candidate id. */
    public record SourceSettings(boolean enabled, EnumMap<DropCategory, Integer> categoryWeights, Map<String, Rule> overrides) {
        public static SourceSettings of(RandomizerSource source, boolean enabled, Map<DropCategory, Integer> weights, Map<String, Rule> overrides) {
            EnumMap<DropCategory, Integer> clamped = new EnumMap<>(DropCategory.class);
            for (DropCategory category : DropCategory.values()) {
                clamped.put(category, Mth.clamp(weights.getOrDefault(category, defaultCategoryWeight(source, category)), 0, MAX_WEIGHT));
            }
            return new SourceSettings(enabled, clamped, new TreeMap<>(overrides));
        }

        public int categoryWeight(DropCategory category) {
            return categoryWeights.get(category);
        }

        public Rule ruleFor(RandomizerSource source, Candidate candidate) {
            return overrides.getOrDefault(candidate.id(), candidate.defaults(source));
        }
    }

    public static int defaultCategoryWeight(RandomizerSource source, DropCategory category) {
        return switch (source) {
            case GLAZED_TERRACOTTA -> switch (category) {
                case BLOCK -> 52;
                case WEAPON, TOOL, ARMOR, ITEM -> 12;
                case FOOD -> 6;
                default -> 10;
            };
            case WARPED_HYPHAE -> switch (category) {
                case FOOD -> 50;
                case POTION, ENCHANTMENT -> 25;
                default -> 10;
            };
            case NOTE_BLOCK -> switch (category) {
                case BLOCK -> 20;
                case WEAPON, ARMOR, ENCHANTMENT -> 15;
                case ITEM -> 17;
                case TOOL -> 8;
                case FOOD -> 6;
                default -> 10;
            };
        };
    }

    private static RandomizerSettings instance;

    private final EnumMap<RandomizerSource, SourceSettings> sources = new EnumMap<>(RandomizerSource.class);

    public RandomizerSettings(Map<RandomizerSource, SourceSettings> sources) {
        for (RandomizerSource source : RandomizerSource.values()) {
            this.sources.put(source, sources.getOrDefault(source, SourceSettings.of(source, true, Map.of(), Map.of())));
        }
    }

    public static synchronized RandomizerSettings get() {
        if (instance == null) instance = load();
        return instance;
    }

    /** Applies new settings right away (pools are rebuilt on the next roll) and saves them. */
    public static synchronized void replace(RandomizerSettings settings) {
        instance = settings;
        settings.save();
        RandomDropPool.invalidate();
    }

    public SourceSettings source(RandomizerSource source) {
        return sources.get(source);
    }

    private static RandomizerSettings load() {
        if (!Files.exists(FILE)) return new RandomizerSettings(Map.of());
        try (Reader reader = Files.newBufferedReader(FILE)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            Map<RandomizerSource, SourceSettings> sources = new EnumMap<>(RandomizerSource.class);
            for (RandomizerSource source : RandomizerSource.values()) {
                JsonObject json = GsonHelper.getAsJsonObject(root, source.key(), null);
                // the first version only had glazed terracotta, stored at the top level
                if (json == null && source == RandomizerSource.GLAZED_TERRACOTTA && (root.has("items") || root.has("categoryWeights"))) {
                    json = root;
                }
                if (json != null) sources.put(source, readSource(source, json));
            }
            return new RandomizerSettings(sources);
        } catch (IOException | RuntimeException e) {
            BedwarsRandomizer.LOGGER.error("Could not read {}, using default randomizer settings", FILE, e);
            return new RandomizerSettings(Map.of());
        }
    }

    private static SourceSettings readSource(RandomizerSource source, JsonObject json) {
        Map<DropCategory, Integer> weights = new EnumMap<>(DropCategory.class);
        JsonObject weightsJson = GsonHelper.getAsJsonObject(json, "categoryWeights", new JsonObject());
        for (DropCategory category : DropCategory.values()) {
            if (weightsJson.has(category.key())) weights.put(category, weightsJson.get(category.key()).getAsInt());
        }
        Map<String, Rule> overrides = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : GsonHelper.getAsJsonObject(json, "items", new JsonObject()).entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject rule = entry.getValue().getAsJsonObject();
            overrides.put(entry.getKey(), new Rule(GsonHelper.getAsBoolean(rule, "enabled", true), GsonHelper.getAsInt(rule, "weight", 1),
                    GsonHelper.getAsInt(rule, "min", 1), GsonHelper.getAsInt(rule, "max", 1)));
        }
        return SourceSettings.of(source, GsonHelper.getAsBoolean(json, "enabled", true), weights, overrides);
    }

    private void save() {
        JsonObject root = new JsonObject();
        sources.forEach((source, settings) -> {
            JsonObject json = new JsonObject();
            json.addProperty("enabled", settings.enabled());
            JsonObject weights = new JsonObject();
            settings.categoryWeights().forEach((category, weight) -> weights.addProperty(category.key(), weight));
            json.add("categoryWeights", weights);
            JsonObject items = new JsonObject();
            settings.overrides().forEach((id, rule) -> {
                JsonObject ruleJson = new JsonObject();
                ruleJson.addProperty("enabled", rule.enabled());
                ruleJson.addProperty("weight", rule.weight());
                ruleJson.addProperty("min", rule.min());
                ruleJson.addProperty("max", rule.max());
                items.add(id, ruleJson);
            });
            json.add("items", items);
            root.add(source.key(), json);
        });
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root));
        } catch (IOException e) {
            BedwarsRandomizer.LOGGER.error("Could not save {}", FILE, e);
        }
    }
}
