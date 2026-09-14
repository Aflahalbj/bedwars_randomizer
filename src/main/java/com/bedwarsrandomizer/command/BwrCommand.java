package com.bedwarsrandomizer.command;

import com.bedwarsrandomizer.drop.RandomDropPool;
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
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

public final class BwrCommand {

    private static final SuggestionProvider<CommandSourceStack> KILLERS = (ctx, builder) -> {
        Set<String> names = new LinkedHashSet<>(ReplayStorage.killerNames());
        names.addAll(List.of(ctx.getSource().getServer().getPlayerNames()));
        return SharedSuggestionProvider.suggest(names, builder);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bwr")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start").executes(BwrCommand::start))
                .then(Commands.literal("stop").executes(BwrCommand::stop))
                .then(Commands.literal("droptest")
                        .executes(ctx -> dropTest(ctx, 1000))
                        .then(Commands.argument("rolls", IntegerArgumentType.integer(1, 100000))
                                .executes(ctx -> dropTest(ctx, IntegerArgumentType.getInteger(ctx, "rolls")))))
                .then(Commands.literal("replay")
                        .then(Commands.literal("kill")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(KILLERS)
                                        .executes(ctx -> playKill(ctx, null))
                                        .then(Commands.argument("viewers", EntityArgument.players())
                                                .executes(ctx -> playKill(ctx, EntityArgument.getPlayers(ctx, "viewers"))))))
                        .then(Commands.literal("list").executes(BwrCommand::list))
                        .then(Commands.literal("stop")
                                .executes(ctx -> stopReplay(ctx, null))
                                .then(Commands.argument("viewers", EntityArgument.players())
                                        .executes(ctx -> stopReplay(ctx, EntityArgument.getPlayers(ctx, "viewers")))))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        ReplayRecorder recorder = ReplayRecorder.get();
        if (recorder.isRecording()) {
            ctx.getSource().sendFailure(Component.literal("Bedwars Randomizer is already running."));
            return 0;
        }
        recorder.start();
        ctx.getSource().sendSuccess(() -> prefix()
                .append(Component.literal("Game started. Recording kill replays.").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        ReplayRecorder recorder = ReplayRecorder.get();
        if (!recorder.isRecording()) {
            ctx.getSource().sendFailure(Component.literal("Bedwars Randomizer is not running."));
            return 0;
        }
        recorder.stop(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> prefix()
                .append(Component.literal("Game stopped. Saved replays are kept.").withStyle(ChatFormatting.YELLOW)), true);
        return 1;
    }

    /** Rolls the random-drop pool many times and prints the distribution, to check and tune rarity. */
    private static int dropTest(CommandContext<CommandSourceStack> ctx, int rolls) {
        ServerLevel level = ctx.getSource().getLevel();
        RandomDropPool pool = RandomDropPool.get(level);
        RandomSource random = level.getRandom();

        Map<Item, Integer> counts = new HashMap<>();
        Map<RandomDropPool.Category, Integer> perCategory = new EnumMap<>(RandomDropPool.Category.class);
        for (int i = 0; i < rolls; i++) {
            ItemStack stack = pool.roll(random);
            if (stack.isEmpty()) continue;
            counts.merge(stack.getItem(), 1, Integer::sum);
            perCategory.merge(pool.categoryOf(stack.getItem()), 1, Integer::sum);
        }

        StringBuilder summary = new StringBuilder("Rolled " + rolls + ":");
        for (RandomDropPool.Category category : RandomDropPool.Category.values()) {
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

    private static int playKill(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> viewers) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "player");
        ReplayData data = ReplayStorage.latest(name);
        if (data == null) {
            source.sendFailure(Component.literal("No recorded kills for " + name
                    + (ReplayRecorder.get().isRecording() ? "." : ". Start recording with /bwr start.")));
            return 0;
        }

        ReplayData packet = ModNetwork.fitToPacket(data);
        if (packet == null) {
            source.sendFailure(Component.literal("That replay is too large to send."));
            return 0;
        }

        Collection<ServerPlayer> targets = viewers != null ? viewers : source.getServer().getPlayerList().getPlayers();
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
                .append(Component.literal(" for " + count + " player(s)").withStyle(ChatFormatting.GRAY)), true);
        if (missingMod > 0) {
            source.sendFailure(Component.literal(missingMod + " player(s) skipped: mod not installed on their client."));
        }
        if (otherDimension > 0) {
            source.sendFailure(Component.literal(otherDimension + " player(s) skipped: in another dimension."));
        }
        return sent;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<ReplayData> replays = ReplayStorage.all();
        if (replays.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No kill replays recorded yet."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Recorded kills (newest first):")
                .withStyle(ChatFormatting.GRAY)), false);
        long now = System.currentTimeMillis();
        for (ReplayData data : replays.subList(0, Math.min(10, replays.size()))) {
            long seconds = (now - data.createdAt) / 1000;
            String command = "/bwr replay kill " + data.killerName;
            MutableComponent line = Component.literal(" ▶ ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(data.killerName).withStyle(ChatFormatting.RED))
                    .append(Component.literal(" ⚔ ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(data.victimName).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("  (" + data.cause.verb + ", " + seconds + "s ago)")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)));
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

    private static MutableComponent prefix() {
        return Component.literal("[BWR] ").withStyle(ChatFormatting.GOLD);
    }

    private BwrCommand() {}
}
