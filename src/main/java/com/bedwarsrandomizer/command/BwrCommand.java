package com.bedwarsrandomizer.command;

import com.bedwarsrandomizer.arena.Arena;
import com.bedwarsrandomizer.game.BedwarsGame;
import com.bedwarsrandomizer.game.GameSettings;
import com.bedwarsrandomizer.network.GameSettingsScreenPacket;
import com.bedwarsrandomizer.network.MapSelectPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.levelgen.Heightmap;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.server.MinecraftServer;
import com.bedwarsrandomizer.drop.DropCategory;
import com.bedwarsrandomizer.drop.RandomDropPool;
import com.bedwarsrandomizer.drop.RandomizerSource;
import com.bedwarsrandomizer.network.RandomizerScreenPacket;
import com.bedwarsrandomizer.network.ModNetwork;
import com.bedwarsrandomizer.replay.ReplayData;
import com.bedwarsrandomizer.replay.ReplayRecorder;
import com.bedwarsrandomizer.replay.ReplayStorage;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.stream.Collectors;

/**
 * {@code /bwr start|stop|droptest} and
 * {@code /bwr replay <targets> kill <killer> <victim> [id]}, {@code /bwr replay [targets] stop}, {@code /bwr replay list}.
 */
public final class BwrCommand {

    /** Selectors with a short explanation, then the online players. */
    private static final SuggestionProvider<CommandSourceStack> TARGETS = (ctx, builder) -> {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        suggest(builder, typed, "@a", "@a - every player on the server");
        suggest(builder, typed, "@p", "@p - the nearest player (you, when you run it yourself)");
        suggest(builder, typed, "@r", "@r - one random player");
        suggest(builder, typed, "@s", "@s - only you");
        for (String name : ctx.getSource().getOnlinePlayerNames()) {
            suggest(builder, typed, name, "Only " + name);
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> KILLERS = (ctx, builder) -> {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String killer : ReplayStorage.killerNames()) {
            int victims = ReplayStorage.victimNames(killer).size();
            suggest(builder, typed, killer, "Killed " + victims + " player(s) in stored replays");
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> VICTIMS = (ctx, builder) -> {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        String killer = StringArgumentType.getString(ctx, "killer");
        for (String victim : ReplayStorage.victimNames(killer)) {
            int count = ReplayStorage.between(killer, victim).size();
            suggest(builder, typed, victim, count + " replay(s)");
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> IDS = (ctx, builder) -> {
        String killer = StringArgumentType.getString(ctx, "killer");
        String victim = StringArgumentType.getString(ctx, "victim");
        long now = System.currentTimeMillis();
        for (ReplayStorage.Entry entry : ReplayStorage.between(killer, victim)) {
            builder.suggest(entry.number(), Component.literal(age(now, entry.data()) + ", " + entry.data().cause.verb));
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> HOSTS = (ctx, builder) -> {
        for (GameSettings.Registered player : GameSettings.get().players()) {
            if (player.host()) builder.suggest(player.name());
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> REGISTERED = (ctx, builder) -> {
        for (GameSettings.Registered player : GameSettings.get().players()) builder.suggest(player.name());
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bwr")
                // registered players and operators
                .then(Commands.literal("gotolobby").executes(BwrCommand::goToLobby))
                .then(Commands.literal("gotoworld").executes(BwrCommand::goToWorld))
                // the [Teleport all to lobby] link
                .then(Commands.literal("lobbyall").requires(BwrCommand::isHostOrOp).executes(BwrCommand::lobbyAll))
                .then(Commands.literal("maps").requires(BwrCommand::isHostOrOp).executes(BwrCommand::openMaps))
                .then(op("regis")
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(ctx -> registerPlayers(ctx, EntityArgument.getPlayers(ctx, "players")))))
                .then(op("regisall").executes(ctx -> registerPlayers(ctx, ctx.getSource().getServer().getPlayerList().getPlayers())))
                .then(op("unregis")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(REGISTERED).executes(BwrCommand::unregisterPlayer)))
                .then(op("unregisall").executes(BwrCommand::unregisterAll))
                .then(op("host")
                        .then(Commands.literal("add")
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(ctx -> addHosts(ctx, EntityArgument.getPlayers(ctx, "players")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(HOSTS).executes(BwrCommand::removeHost)))
                        .then(Commands.literal("clear").executes(BwrCommand::clearHosts)))
                // the [Next round] / [End round] links
                .then(Commands.literal("nextround").requires(BwrCommand::isHostOrOp).executes(BwrCommand::nextRound))
                .then(Commands.literal("endround").requires(BwrCommand::isHostOrOp).executes(BwrCommand::endRounds))
                .then(op("start").executes(BwrCommand::start))
                .then(op("stop").executes(BwrCommand::stop))
                .then(op("droptest")
                        .executes(ctx -> dropTest(ctx, 1000))
                        .then(Commands.argument("rolls", IntegerArgumentType.integer(1, 100000))
                                .executes(ctx -> dropTest(ctx, IntegerArgumentType.getInteger(ctx, "rolls")))))
                .then(op("setting")
                        .executes(BwrCommand::openGameSettings)
                        .then(Commands.literal("randomizer").executes(BwrCommand::openRandomizerSettings)))
                .then(op("replay")
                        .then(Commands.literal("record").executes(BwrCommand::recordReplays))
                        .then(Commands.literal("list").executes(BwrCommand::list))
                        .then(Commands.literal("stop").executes(ctx -> stopReplay(ctx, null)))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .suggests(TARGETS)
                                .then(Commands.literal("kill")
                                        .then(Commands.argument("killer", StringArgumentType.word())
                                                .suggests(KILLERS)
                                                .then(Commands.argument("victim", StringArgumentType.word())
                                                        .suggests(VICTIMS)
                                                        .executes(ctx -> playKill(ctx, null))
                                                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                                                .suggests(IDS)
                                                                .executes(ctx -> playKill(ctx, IntegerArgumentType.getInteger(ctx, "id")))))))
                                .then(Commands.literal("stop")
                                        .executes(ctx -> stopReplay(ctx, EntityArgument.getPlayers(ctx, "targets")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> op(String name) {
        return Commands.literal(name).requires(source -> source.hasPermission(2));
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        Component error = BedwarsGame.get().start(ctx.getSource().getServer(), ctx.getSource().getPlayer());
        if (error != null) {
            ctx.getSource().sendFailure(error);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Bed Wars is starting!").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        boolean stopped = BedwarsGame.get().stop(server);
        if (!stopped && ReplayRecorder.get().isRecording()) {
            ReplayRecorder.get().stop(server);
            stopped = true;
        }
        if (!stopped) {
            ctx.getSource().sendFailure(Component.literal("No game is running."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> prefix()
                .append(Component.literal("Game stopped. Saved replays are kept.").withStyle(ChatFormatting.YELLOW)), true);
        return 1;
    }

    /** Kill replay recording without a game (dev tests). */
    private static int recordReplays(CommandContext<CommandSourceStack> ctx) {
        ReplayRecorder recorder = ReplayRecorder.get();
        if (recorder.isRecording()) {
            ctx.getSource().sendFailure(Component.literal("Already recording kill replays."));
            return 0;
        }
        recorder.start();
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Recording kill replays.").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private static int registerPlayers(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        GameSettings settings = GameSettings.get();
        int added = 0;
        for (ServerPlayer player : targets) {
            if (settings.register(player)) added++;
            BedwarsGame.notifyRegistered(player);
        }
        settings.save();
        BedwarsGame.sendLobbyLink(ctx.getSource().getServer(), ctx.getSource().getPlayer());
        int newPlayers = added;
        String names = settings.players().stream().map(GameSettings.Registered::name).collect(Collectors.joining(", "));
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Registered " + targets.size() + " player(s), " + newPlayers
                + " new. Now registered: " + names).withStyle(ChatFormatting.GREEN)), true);
        return targets.size();
    }

    private static int unregisterPlayer(CommandContext<CommandSourceStack> ctx) {
        GameSettings settings = GameSettings.get();
        String name = StringArgumentType.getString(ctx, "player");
        GameSettings.Registered player = settings.playerByName(name);
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal(name + " is not registered."));
            return 0;
        }
        settings.unregister(player.id());
        settings.save();
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Unregistered " + player.name() + ".").withStyle(ChatFormatting.YELLOW)), true);
        return 1;
    }

    private static int unregisterAll(CommandContext<CommandSourceStack> ctx) {
        GameSettings settings = GameSettings.get();
        int count = settings.players().size();
        settings.unregisterAll();
        settings.save();
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Unregistered all " + count + " player(s).").withStyle(ChatFormatting.YELLOW)), true);
        return count;
    }

    private static boolean isHostOrOp(CommandSourceStack source) {
        return source.hasPermission(2) || (source.getEntity() instanceof ServerPlayer player && GameSettings.get().isHost(player.getUUID()));
    }

    private static int addHosts(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        GameSettings settings = GameSettings.get();
        for (ServerPlayer player : targets) {
            boolean wasRegistered = settings.player(player.getUUID()) != null;
            settings.setHost(player, true);
            if (!wasRegistered) BedwarsGame.notifyRegistered(player);
            player.sendSystemMessage(Component.literal("You are a Bed Wars host: you watch the rounds and decide when the next one starts.")
                    .withStyle(ChatFormatting.GOLD));
        }
        settings.save();
        BedwarsGame.sendLobbyLink(ctx.getSource().getServer(), ctx.getSource().getPlayer());
        String names = targets.stream().map(player -> player.getGameProfile().getName()).collect(Collectors.joining(", "));
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Host(s): " + names + ". Hosts don't play.").withStyle(ChatFormatting.GREEN)), true);
        return targets.size();
    }

    private static int removeHost(CommandContext<CommandSourceStack> ctx) {
        GameSettings settings = GameSettings.get();
        String name = StringArgumentType.getString(ctx, "player");
        GameSettings.Registered player = settings.playerByName(name);
        if (player == null || !player.host()) {
            ctx.getSource().sendFailure(Component.literal(name + " is not a host."));
            return 0;
        }
        settings.setHost(player.id(), false);
        settings.save();
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal(player.name() + " is no longer a host and plays again.").withStyle(ChatFormatting.YELLOW)), true);
        return 1;
    }

    private static int clearHosts(CommandContext<CommandSourceStack> ctx) {
        GameSettings settings = GameSettings.get();
        int count = settings.clearHosts();
        settings.save();
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Removed " + count + " host(s).").withStyle(ChatFormatting.YELLOW)), true);
        return count;
    }

    private static int nextRound(CommandContext<CommandSourceStack> ctx) {
        Component error = BedwarsGame.get().nextRound(ctx.getSource().getServer(), ctx.getSource().getPlayer());
        if (error != null) {
            ctx.getSource().sendFailure(error);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Next round starting!").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private static int endRounds(CommandContext<CommandSourceStack> ctx) {
        BedwarsGame.get().endRounds(ctx.getSource().getServer());
        return 1;
    }

    /** Registered players and operators may move themselves, but not while they play (or host) a round. */
    private static boolean mayTeleportSelf(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        if (GameSettings.get().player(player.getUUID()) == null && !ctx.getSource().hasPermission(2)) {
            ctx.getSource().sendFailure(Component.literal("You are not registered for Bed Wars."));
            return false;
        }
        BedwarsGame game = BedwarsGame.get();
        if (game.isActive() && (game.teamOf(player.getUUID()) != null || game.isHostInRound(player.getUUID()))) {
            ctx.getSource().sendFailure(Component.literal("You are in the running round."));
            return false;
        }
        return true;
    }

    private static int goToLobby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!mayTeleportSelf(ctx, player)) return 0;
        if (!Arena.teleportToLobby(player)) {
            ctx.getSource().sendFailure(Component.literal("The arena dimension is missing."));
            return 0;
        }
        player.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Welcome to the lobby!").withStyle(ChatFormatting.GREEN)), false);
        return 1;
    }

    private static int goToWorld(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!mayTeleportSelf(ctx, player)) return 0;
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        BlockPos spawn = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, overworld.getSharedSpawnPos());
        player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), player.getXRot());
        player.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Back in the overworld.").withStyle(ChatFormatting.GREEN)), false);
        return 1;
    }

    /** {@code /bwr maps}: the map choice screen, only changing the arena map. */
    private static int openMaps(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!ModNetwork.hasMod(player)) {
            ctx.getSource().sendFailure(Component.literal("The map screen needs the Bedwars Randomizer mod on your client."));
            return 0;
        }
        if (BedwarsGame.get().isActive()) {
            ctx.getSource().sendFailure(Component.literal("A round is running; the map can't change now."));
            return 0;
        }
        MinecraftServer server = ctx.getSource().getServer();
        ModNetwork.sendMapSelect(player, new MapSelectPacket(Arena.availableMaps(server), GameSettings.get().map, false));
        return 1;
    }

    /** [Teleport all to lobby]: the map choice screen, or straight to the lobby when the clicker has no mod. */
    private static int lobbyAll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        if (BedwarsGame.get().isActive()) {
            ctx.getSource().sendFailure(Component.literal("A round is running right now."));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null && ModNetwork.hasMod(player)) {
            ModNetwork.sendMapSelect(player, new MapSelectPacket(Arena.availableMaps(server), GameSettings.get().map, true));
            return 1;
        }
        int count = BedwarsGame.teleportAllToLobby(server);
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Teleported " + count + " registered player(s) to the lobby.")
                .withStyle(ChatFormatting.GREEN)), true);
        return count;
    }

    private static int openGameSettings(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!ModNetwork.hasMod(player)) {
            ctx.getSource().sendFailure(Component.literal("The settings screen needs the Bedwars Randomizer mod on your client."));
            return 0;
        }
        ModNetwork.sendGameSettingsScreen(player, GameSettingsScreenPacket.create(ctx.getSource().getServer()));
        return 1;
    }

    private static int openRandomizerSettings(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!ModNetwork.hasMod(player)) {
            ctx.getSource().sendFailure(Component.literal("The settings screen needs the Bedwars Randomizer mod on your client."));
            return 0;
        }
        ModNetwork.sendRandomizerScreen(player, RandomizerScreenPacket.create(player.serverLevel()));
        return 1;
    }

    /** Rolls the random-drop pool many times and prints the distribution, to check and tune rarity. */
    private static int dropTest(CommandContext<CommandSourceStack> ctx, int rolls) {
        ServerLevel level = ctx.getSource().getLevel();
        RandomDropPool pool = RandomDropPool.get(level, RandomizerSource.GLAZED_TERRACOTTA);
        RandomSource random = level.getRandom();

        Map<Item, Integer> counts = new HashMap<>();
        Map<DropCategory, Integer> perCategory = new EnumMap<>(DropCategory.class);
        for (int i = 0; i < rolls; i++) {
            ItemStack stack = pool.roll(random, null);
            if (stack.isEmpty()) continue;
            counts.merge(stack.getItem(), 1, Integer::sum);
            DropCategory category = pool.categoryOf(stack.getItem());
            if (category != null) perCategory.merge(category, 1, Integer::sum);
        }

        StringBuilder summary = new StringBuilder("Rolled " + rolls + ":");
        for (DropCategory category : DropCategory.values()) {
            summary.append(String.format(Locale.ROOT, "  %s %.1f%%", category.name().toLowerCase(Locale.ROOT),
                    100.0 * perCategory.getOrDefault(category, 0) / rolls));
        }
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal(summary.toString()).withStyle(ChatFormatting.GRAY)), false);

        List<Map.Entry<Item, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        StringBuilder topLine = new StringBuilder("Most common:");
        for (Map.Entry<Item, Integer> entry : top.subList(0, Math.min(8, top.size()))) {
            topLine.append(' ').append(ForgeRegistries.ITEMS.getKey(entry.getKey()).getPath()).append('=').append(entry.getValue());
        }
        ctx.getSource().sendSuccess(() -> Component.literal(topLine.toString()).withStyle(ChatFormatting.DARK_GRAY), false);

        StringBuilder samples = new StringBuilder("Samples:");
        for (Item item : List.of(Items.WHITE_WOOL, Items.OAK_PLANKS, Items.END_STONE, Items.OBSIDIAN, Items.WOODEN_SWORD,
                Items.STONE_SWORD, Items.IRON_SWORD, Items.LEATHER_CHESTPLATE, Items.IRON_CHESTPLATE,
                Items.DIAMOND_SWORD, Items.NETHERITE_CHESTPLATE, Items.BEDROCK, Items.TRIDENT)) {
            samples.append(' ').append(ForgeRegistries.ITEMS.getKey(item).getPath()).append('=').append(counts.getOrDefault(item, 0));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(samples.toString()).withStyle(ChatFormatting.DARK_GRAY), false);
        return counts.size();
    }

    private static int playKill(CommandContext<CommandSourceStack> ctx, Integer id) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        String killer = StringArgumentType.getString(ctx, "killer");
        String victim = StringArgumentType.getString(ctx, "victim");

        ReplayStorage.Entry entry = ReplayStorage.find(killer, victim, id);
        if (entry == null) {
            List<ReplayStorage.Entry> available = ReplayStorage.between(killer, victim);
            if (available.isEmpty()) {
                source.sendFailure(Component.literal("No replay of " + killer + " killing " + victim
                        + (ReplayRecorder.get().isRecording() ? "." : ". Start recording with /bwr start.")));
            } else {
                String numbers = available.stream().map(e -> String.valueOf(e.number())).collect(Collectors.joining(", "));
                source.sendFailure(Component.literal("No replay #" + id + " of " + killer + " killing " + victim
                        + ". Available: " + numbers));
            }
            return 0;
        }
        ReplayData data = entry.data();

        ReplayData packet = ModNetwork.fitToPacket(data);
        if (packet == null) {
            source.sendFailure(Component.literal("That replay is too large to send."));
            return 0;
        }

        int sent = 0;
        int missingMod = 0;
        int otherDimension = 0;
        for (ServerPlayer player : targets) {
            if (!ModNetwork.hasMod(player)) {
                missingMod++;
            } else if (!player.level().dimension().equals(data.dimension)) {
                otherDimension++;
            } else {
                ModNetwork.sendReplay(player, packet);
                sent++;
            }
        }

        int count = sent;
        source.sendSuccess(() -> prefix()
                .append(Component.literal("Playing ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(data.killerName).withStyle(ChatFormatting.RED))
                .append(Component.literal(" ⚔ ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(data.victimName).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" #" + entry.number() + " for " + count + " player(s)").withStyle(ChatFormatting.GRAY)), true);
        if (missingMod > 0) {
            source.sendFailure(Component.literal(missingMod + " player(s) skipped: mod not installed on their client."));
        }
        if (otherDimension > 0) {
            source.sendFailure(Component.literal(otherDimension + " player(s) skipped: in another dimension."));
        }
        return sent;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<ReplayStorage.Entry> replays = ReplayStorage.all();
        if (replays.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No kill replays recorded yet."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Recorded kills (newest first, click to watch):")
                .withStyle(ChatFormatting.GRAY)), false);
        long now = System.currentTimeMillis();
        for (ReplayStorage.Entry entry : replays.subList(0, Math.min(10, replays.size()))) {
            ReplayData data = entry.data();
            String command = watchCommand("@s", data, entry.number());
            MutableComponent line = Component.literal(" ▶ ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(data.killerName).withStyle(ChatFormatting.RED))
                    .append(Component.literal(" ⚔ ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(data.victimName).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" #" + entry.number()).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("  (" + data.cause.verb + ", " + age(now, data) + ")")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return replays.size();
    }

    private static int stopReplay(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> viewers) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = viewers != null ? viewers : ctx.getSource().getServer().getPlayerList().getPlayers();
        for (ServerPlayer player : targets) {
            if (ModNetwork.hasMod(player)) {
                ModNetwork.sendStop(player);
            }
        }
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Replay stopped.").withStyle(ChatFormatting.GRAY)), true);
        return targets.size();
    }

    /** {@code /bwr replay <targets> kill <killer> <victim> <id>} for a stored replay. */
    public static String watchCommand(String targets, ReplayData data, int number) {
        return "/bwr replay " + targets + " kill " + data.killerName + " " + data.victimName + " " + number;
    }

    private static void suggest(SuggestionsBuilder builder, String typed, String text, String tooltip) {
        if (text.toLowerCase(Locale.ROOT).startsWith(typed)) {
            builder.suggest(text, Component.literal(tooltip));
        }
    }

    private static String age(long now, ReplayData data) {
        long seconds = Math.max(0, (now - data.createdAt) / 1000);
        return seconds < 60 ? seconds + "s ago" : (seconds / 60) + "m ago";
    }

    private static MutableComponent prefix() {
        return Component.literal("[BWR] ").withStyle(ChatFormatting.GOLD);
    }

    private BwrCommand() {}
}
