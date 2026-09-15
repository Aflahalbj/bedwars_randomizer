package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.drop.RandomizerSource;
import com.bedwarsrandomizer.game.BedwarsGame;
import com.bedwarsrandomizer.network.GameSettingsSavePacket;
import com.bedwarsrandomizer.network.GameSettingsScreenPacket;
import com.bedwarsrandomizer.network.ModNetwork;
import com.bedwarsrandomizer.network.RefillNowPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;

/** {@code /bwr setting}: game timers, team size, refills and the registered players with their team and role. */
public class GameSettingsScreen extends Screen {
    private static final String KEY = "gui.bedwarsrandomizer.game.";
    private static final String[] NUMBER_KEYS = {"countdown", "respawn", "team_size"};
    private static final int[] NUMBER_DEFAULTS = {5, 5, 1};
    private static final int ROW_WIDTH = 360;
    private static final int LIST_TOP = 124;
    private static final List<Optional<DyeColor>> TEAMS = new ArrayList<>();

    static {
        TEAMS.add(Optional.empty());
        BedwarsGame.ISLAND_ORDER.forEach(color -> TEAMS.add(Optional.of(color)));
    }

    private static final class Row {
        @Nullable
        final UUID id;
        final String name;
        final boolean online;
        @Nullable
        DyeColor team;
        boolean host;

        Row(@Nullable UUID id, String name, @Nullable DyeColor team, boolean host, boolean online) {
            this.id = id;
            this.name = name;
            this.team = team;
            this.host = host;
            this.online = online;
        }
    }

    private final String[] numbers = new String[NUMBER_KEYS.length];
    private final int spawnDistance;
    private final String[] refill = new String[RandomizerSource.values().length];
    private final List<Row> rows = new ArrayList<>();
    private final List<String> unregisteredOnline;
    private final boolean gameRunning;
    private String nameInput = "";
    private boolean listDirty;
    private PlayerList list;

    public static void open(GameSettingsScreenPacket packet) {
        Minecraft.getInstance().setScreen(new GameSettingsScreen(packet));
    }

    private GameSettingsScreen(GameSettingsScreenPacket packet) {
        super(Component.translatable(KEY + "title"));
        int[] values = {packet.startCountdown(), packet.respawnSeconds(), packet.playersPerTeam()};
        for (int i = 0; i < values.length; i++) numbers[i] = String.valueOf(values[i]);
        spawnDistance = packet.spawnDistance();
        for (int i = 0; i < refill.length; i++) refill[i] = String.valueOf(packet.refillSeconds()[i]);
        for (GameSettingsScreenPacket.PlayerRow row : packet.players()) {
            rows.add(new Row(row.id(), row.name(), row.team(), row.host(), row.online()));
        }
        unregisteredOnline = new ArrayList<>(packet.unregisteredOnline());
        gameRunning = packet.gameRunning();
    }

    @Override
    protected void init() {
        int center = width / 2;
        list = new PlayerList(minecraft, width, height, LIST_TOP, height - 34, 22);
        addRenderableWidget(list);
        rebuildList();

        Component[] numberLabels = labels(NUMBER_KEYS);
        int[] numberX = groupX(numberLabels);
        for (int i = 0; i < NUMBER_KEYS.length; i++) {
            int index = i;
            EditBox box = numberBox(numberX[i] + font.width(numberLabels[i]) + 4, 20, numbers[i], text -> numbers[index] = text);
            box.setTooltip(Tooltip.create(Component.translatable(KEY + NUMBER_KEYS[i] + ".tooltip")));
            addRenderableWidget(box);
        }

        RandomizerSource[] sources = RandomizerSource.values();
        Component[] refillLabels = refillLabels();
        int[] refillX = groupX(refillLabels);
        for (RandomizerSource source : sources) {
            int index = source.ordinal();
            EditBox box = numberBox(refillX[index] + font.width(refillLabels[index]) + 4, 42, refill[index], text -> refill[index] = text);
            box.setTooltip(Tooltip.create(Component.translatable(KEY + "refill.tooltip")));
            addRenderableWidget(box);
            addRenderableWidget(Button.builder(Component.translatable(KEY + "refill_now." + source.key()),
                            b -> ModNetwork.CHANNEL.sendToServer(new RefillNowPacket(source)))
                    .bounds(center - 180 + index * 121, 62, 118, 20)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "refill_now.tooltip")))
                    .build());
        }

        EditBox nameBox = new EditBox(font, center - 180, 88, 120, 18, Component.translatable(KEY + "name"));
        nameBox.setHint(Component.translatable(KEY + "name"));
        nameBox.setMaxLength(16);
        nameBox.setValue(nameInput);
        nameBox.setResponder(text -> nameInput = text);
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable(KEY + "add"), b -> addName(nameInput))
                .bounds(center - 56, 87, 50, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(KEY + "add_online"), b -> addOnline())
                .bounds(center - 2, 87, 110, 20)
                .tooltip(Tooltip.create(Component.translatable(KEY + "add_online.tooltip")))
                .build());

        int bottom = height - 27;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "randomizer"), b -> minecraft.player.connection.sendCommand("bwr setting randomizer"))
                .bounds(center - 154, bottom, 100, 20)
                .tooltip(Tooltip.create(Component.translatable(KEY + "randomizer.tooltip")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY + "save"), b -> save())
                .bounds(center - 50, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(center + 54, bottom, 100, 20).build());
    }

    private static Component[] labels(String[] keys) {
        return Arrays.stream(keys).map(key -> Component.translatable(KEY + key)).toArray(Component[]::new);
    }

    private static Component[] refillLabels() {
        return Arrays.stream(RandomizerSource.values()).map(source -> Component.translatable(KEY + "refill." + source.key())).toArray(Component[]::new);
    }

    /** Left edges of centered "label [box]" groups. */
    private int[] groupX(Component[] labels) {
        int boxWidth = 26, gap = 12, labelGap = 4;
        int total = gap * (labels.length - 1);
        int[] widths = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            widths[i] = font.width(labels[i]) + labelGap + boxWidth;
            total += widths[i];
        }
        int[] xs = new int[labels.length];
        int x = width / 2 - total / 2;
        for (int i = 0; i < labels.length; i++) {
            xs[i] = x;
            x += widths[i] + gap;
        }
        return xs;
    }

    private EditBox numberBox(int x, int y, String value, Consumer<String> responder) {
        EditBox box = new EditBox(font, x, y - 1, 26, 16, Component.empty());
        box.setMaxLength(4);
        box.setFilter(text -> text.matches("\\d*"));
        box.setValue(value);
        box.setResponder(responder);
        return box;
    }

    private void addName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || rows.stream().anyMatch(row -> row.name.equalsIgnoreCase(trimmed))) return;
        rows.add(new Row(null, trimmed, null, false, unregisteredOnline.stream().anyMatch(online -> online.equalsIgnoreCase(trimmed))));
        nameInput = "";
        rebuildWidgets();
    }

    private void addOnline() {
        for (String name : unregisteredOnline) {
            if (rows.stream().noneMatch(row -> row.name.equalsIgnoreCase(name))) rows.add(new Row(null, name, null, false, true));
        }
        rebuildList();
    }

    private void rebuildList() {
        if (list == null) return;
        List<PlayerEntry> entries = new ArrayList<>();
        for (Row row : rows) entries.add(new PlayerEntry(row));
        list.setRows(entries);
    }

    private void save() {
        int[] values = new int[NUMBER_KEYS.length];
        for (int i = 0; i < values.length; i++) values[i] = parse(numbers[i], NUMBER_DEFAULTS[i]);
        int[] refillSeconds = new int[refill.length];
        for (int i = 0; i < refill.length; i++) refillSeconds[i] = parse(refill[i], 30);
        List<GameSettingsScreenPacket.PlayerRow> players = rows.stream()
                .map(row -> new GameSettingsScreenPacket.PlayerRow(row.id, row.name, row.team, row.host, row.online)).toList();
        ModNetwork.CHANNEL.sendToServer(new GameSettingsSavePacket(values[0], values[1], spawnDistance, values[2], refillSeconds, players));
        onClose();
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Component teamLabel(Optional<DyeColor> team) {
        return team.<Component>map(BedwarsGame::teamName).orElseGet(() -> Component.translatable(KEY + "team.auto"));
    }

    @Override
    public void tick() {
        super.tick();
        if (listDirty) {
            listDirty = false;
            rebuildList();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int center = width / 2;
        graphics.drawCenteredString(font, title, center, 6, 0xFFFFFF);

        Component[] numberLabels = labels(NUMBER_KEYS);
        int[] numberX = groupX(numberLabels);
        for (int i = 0; i < numberLabels.length; i++) graphics.drawString(font, numberLabels[i], numberX[i], 23, 0xA0A0A0);
        Component[] refillLabels = refillLabels();
        int[] refillX = groupX(refillLabels);
        for (int i = 0; i < refillLabels.length; i++) graphics.drawString(font, refillLabels[i], refillX[i], 45, 0xA0A0A0);

        int rowLeft = list.getRowLeft();
        graphics.drawString(font, Component.translatable(KEY + "players", rows.size()), rowLeft + 4, LIST_TOP - 11, 0xA0A0A0);
        graphics.drawString(font, Component.translatable(KEY + "team"), rowLeft + 150, LIST_TOP - 11, 0xA0A0A0);
        graphics.drawString(font, Component.translatable(KEY + "role"), rowLeft + 262, LIST_TOP - 11, 0xA0A0A0);
        if (gameRunning) {
            graphics.drawCenteredString(font, Component.translatable(KEY + "running"), center, height - 42, 0xFFE080);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class PlayerList extends ContainerObjectSelectionList<PlayerEntry> {
        PlayerList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
        }

        void setRows(List<PlayerEntry> entries) {
            replaceEntries(entries);
        }

        @Override
        public int getRowWidth() {
            return ROW_WIDTH;
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + ROW_WIDTH / 2 + 10;
        }
    }

    private final class PlayerEntry extends ContainerObjectSelectionList.Entry<PlayerEntry> {
        private final Row row;
        private final ChoiceButton<Optional<DyeColor>> team;
        private final ChoiceButton<Boolean> role;
        private final Button remove;
        private final List<AbstractWidget> widgets;

        PlayerEntry(Row row) {
            this.row = row;
            team = new ChoiceButton<>(0, 0, 108, 18, null, TEAMS, Optional.ofNullable(row.team), GameSettingsScreen::teamLabel,
                    value -> row.team = value.orElse(null));
            team.setTooltip(Tooltip.create(Component.translatable(KEY + "team.tooltip")));
            role = new ChoiceButton<>(0, 0, 60, 18, null, List.of(false, true), row.host,
                    host -> Component.translatable(KEY + (host ? "role.host" : "role.player")), value -> row.host = value);
            role.setTooltip(Tooltip.create(Component.translatable(KEY + "role.tooltip")));
            remove = Button.builder(Component.literal("X"), b -> {
                rows.remove(row);
                listDirty = true;
            }).bounds(0, 0, 20, 18).tooltip(Tooltip.create(Component.translatable(KEY + "remove"))).build();
            widgets = List.of(team, role, remove);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            String note = row.id == null ? " (new)" : row.online ? "" : " (offline)";
            graphics.drawString(font, font.plainSubstrByWidth(row.name + note, 142), left + 4, top + 5, row.online ? 0xFFFFFF : 0x909090);
            team.setX(left + 150);
            team.setY(top);
            role.setX(left + 262);
            role.setY(top);
            remove.setX(left + 326);
            remove.setY(top);
            for (AbstractWidget widget : widgets) widget.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }
}
