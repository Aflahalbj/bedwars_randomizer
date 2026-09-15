package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.drop.DropCategory;
import com.bedwarsrandomizer.drop.RandomizerSettings.Rule;
import com.bedwarsrandomizer.drop.RandomizerSource;
import com.bedwarsrandomizer.network.ModNetwork;
import com.bedwarsrandomizer.network.RandomizerSavePacket;
import com.bedwarsrandomizer.network.RandomizerScreenPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;

/**
 * {@code /bwr setting randomizer}: pick a randomizer block, turn it on/off and edit every item's chance and drop count.
 * Column titles sort the list.
 */
public class RandomizerScreen extends Screen {
    private static final String KEY = "gui.bedwarsrandomizer.randomizer.";
    private static final DropCategory[] CATEGORIES = DropCategory.values();
    private static final int ROW_WIDTH = 360;
    private static final int HEADER_Y = 64;
    private static final int LIST_TOP = 76;

    private enum Column {
        ITEM(20, 126, "col.item"), CHANCE(150, 50, "col.chance"), ON(204, 28, "col.on"),
        WEIGHT(236, 36, "col.weight"), MIN(275, 26, "col.min"), MAX(303, 26, "col.max");

        final int x;
        final int width;
        final String key;

        Column(int x, int width, String key) {
            this.x = x;
            this.width = width;
            this.key = key;
        }
    }

    /** All items, only items that are on, or one category. */
    private record Filter(@Nullable DropCategory category, boolean onlyOn) {
        Component label() {
            if (category != null) return categoryName(category);
            return Component.translatable(KEY + (onlyOn ? "filter.on" : "filter.all"));
        }
    }

    private static final class RowInfo {
        final String id;
        final ItemStack stack;
        final DropCategory category;
        final String name;
        final String searchText;

        RowInfo(RandomizerScreenPacket.Row row) {
            id = row.id();
            stack = row.stack();
            category = row.category();
            name = displayName(id, stack);
            searchText = (name + " " + id).toLowerCase(Locale.ROOT);
        }
    }

    /** One item's editable rule in one randomizer, kept as text while typing. */
    private static final class RowState {
        final RowInfo info;
        final Rule defaults;
        boolean enabled;
        String weight, min, max;

        RowState(RowInfo info, Rule defaults, Rule current) {
            this.info = info;
            this.defaults = defaults;
            set(current);
        }

        void set(Rule rule) {
            enabled = rule.enabled();
            weight = String.valueOf(rule.weight());
            min = String.valueOf(rule.min());
            max = String.valueOf(rule.max());
        }

        Rule rule() {
            return new Rule(enabled, parse(weight, 0), parse(min, 1), parse(max, 1));
        }
    }

    private static final class SourceState {
        boolean enabled;
        final String[] weights = new String[CATEGORIES.length];
        final int[] defaultWeights;
        final List<RowState> rows = new ArrayList<>();

        SourceState(RandomizerScreenPacket.SourceData data, List<RowInfo> infos) {
            enabled = data.enabled();
            defaultWeights = data.defaultCategoryWeights();
            for (int i = 0; i < CATEGORIES.length; i++) weights[i] = String.valueOf(data.categoryWeights()[i]);
            for (int i = 0; i < infos.size(); i++) rows.add(new RowState(infos.get(i), data.defaults()[i], data.current()[i]));
        }
    }

    private final EnumMap<RandomizerSource, SourceState> states = new EnumMap<>(RandomizerSource.class);
    private RandomizerSource source = RandomizerSource.GLAZED_TERRACOTTA;
    private String search = "";
    private Filter filter = new Filter(null, false);
    @Nullable
    private Column sortColumn;
    private boolean descending;
    private RuleList list;
    /** Widgets are rebuilt on the next tick, not while a click is still being handled. */
    private boolean rebuildPending;

    private final double[] categoryTotals = new double[CATEGORIES.length];
    private double categoryWeightTotal;

    public static void open(RandomizerScreenPacket packet) {
        Minecraft.getInstance().setScreen(new RandomizerScreen(packet));
    }

    private RandomizerScreen(RandomizerScreenPacket packet) {
        super(Component.translatable(KEY + "title"));
        List<RowInfo> infos = packet.rows().stream().map(RowInfo::new).toList();
        for (RandomizerSource s : RandomizerSource.values()) {
            states.put(s, new SourceState(packet.sources().get(s.ordinal()), infos));
        }
    }

    private static String displayName(String id, ItemStack stack) {
        if (stack.getItem() instanceof EnchantedBookItem) {
            Map<Enchantment, Integer> enchantments = EnchantmentHelper.deserializeEnchantments(EnchantedBookItem.getEnchantments(stack));
            if (!enchantments.isEmpty()) {
                Map.Entry<Enchantment, Integer> first = enchantments.entrySet().iterator().next();
                return first.getKey().getFullname(first.getValue()).getString() + " (Book)";
            }
        }
        String name = stack.getHoverName().getString();
        int hash = id.indexOf('#');
        if (hash >= 0) {
            String variant = id.substring(hash + 1);
            String path = variant.substring(variant.indexOf(':') + 1);
            if (path.startsWith("strong_")) name += " II";
            else if (path.startsWith("long_")) name += " (Long)";
        }
        return name;
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Component categoryName(DropCategory category) {
        return Component.translatable(KEY + "category." + category.key());
    }

    private SourceState state() {
        return states.get(source);
    }

    @Override
    protected void init() {
        int center = width / 2;
        list = new RuleList(minecraft, width, height, LIST_TOP, height - 34, 22);
        addRenderableWidget(list);
        rebuildList();

        addRenderableWidget(new ChoiceButton<>(center - 180, 18, 140, 20, Component.translatable(KEY + "source"),
                List.of(RandomizerSource.values()), source, s -> Component.translatable(KEY + "source." + s.key()), value -> {
                    source = value;
                    rebuildPending = true;
                }));
        addRenderableWidget(ChoiceButton.onOff(center - 36, 18, 100, 20, Component.translatable(KEY + "enabled"), state().enabled,
                value -> state().enabled = value));

        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter(null, false));
        filters.add(new Filter(null, true));
        for (DropCategory category : CATEGORIES) filters.add(new Filter(category, false));
        ChoiceButton<Filter> filterButton = new ChoiceButton<>(center + 68, 18, 112, 20, Component.translatable(KEY + "filter"),
                filters, filter, Filter::label, value -> {
                    filter = value;
                    rebuildPending = true;
                });
        filterButton.setTooltip(Tooltip.create(Component.translatable(KEY + "filter.tooltip")));
        addRenderableWidget(filterButton);

        EditBox searchBox = new EditBox(font, center - 180, 41, filter.category() != null ? 214 : 360, 18, Component.translatable(KEY + "search"));
        searchBox.setHint(Component.translatable(KEY + "search"));
        searchBox.setValue(search);
        searchBox.setResponder(text -> {
            search = text;
            rebuildList();
        });
        addRenderableWidget(searchBox);

        if (filter.category() != null) {
            int index = filter.category().ordinal();
            EditBox weightBox = numberBox(center + 150, 42, 30, 4, state().weights[index], text -> state().weights[index] = text);
            weightBox.setTooltip(Tooltip.create(Component.translatable(KEY + "category_weight.tooltip")));
            addRenderableWidget(weightBox);
        }

        int bottom = height - 27;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "reset_all"), b -> resetSource())
                .bounds(center - 154, bottom, 100, 20)
                .tooltip(Tooltip.create(Component.translatable(KEY + "reset_all.tooltip")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY + "save"), b -> save())
                .bounds(center - 50, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(center + 54, bottom, 100, 20).build());
    }

    private EditBox numberBox(int x, int y, int width, int maxLength, String value, Consumer<String> responder) {
        EditBox box = new EditBox(font, x, y, width, 16, Component.empty());
        box.setMaxLength(maxLength);
        box.setFilter(text -> text.matches("\\d*"));
        box.setValue(value);
        box.setResponder(responder);
        return box;
    }

    private void rebuildList() {
        if (list == null) return;
        recomputeTotals();
        String query = search.trim().toLowerCase(Locale.ROOT);
        List<RowState> shown = new ArrayList<>();
        for (RowState row : state().rows) {
            if (filter.category() != null && row.info.category != filter.category()) continue;
            if (filter.onlyOn() && !row.enabled) continue;
            if (!query.isEmpty() && !row.info.searchText.contains(query)) continue;
            shown.add(row);
        }
        Comparator<RowState> comparator = comparator();
        if (comparator != null) shown.sort(comparator);
        List<RuleEntry> entries = new ArrayList<>(shown.size());
        for (RowState row : shown) entries.add(new RuleEntry(row));
        list.setRows(entries);
    }

    @Nullable
    private Comparator<RowState> comparator() {
        if (sortColumn == null) return null;
        Comparator<RowState> comparator = switch (sortColumn) {
            case ITEM -> Comparator.comparing((RowState row) -> row.info.name.toLowerCase(Locale.ROOT));
            case CHANCE -> Comparator.comparingDouble(this::chance);
            case ON -> Comparator.comparing((RowState row) -> row.enabled);
            case WEIGHT -> Comparator.comparingInt((RowState row) -> parse(row.weight, 0));
            case MIN -> Comparator.comparingInt((RowState row) -> parse(row.min, 1));
            case MAX -> Comparator.comparingInt((RowState row) -> parse(row.max, 1));
        };
        if (descending) comparator = comparator.reversed();
        return comparator.thenComparing(row -> row.info.name.toLowerCase(Locale.ROOT));
    }

    private void resetSource() {
        SourceState state = state();
        state.enabled = true;
        for (int i = 0; i < CATEGORIES.length; i++) state.weights[i] = String.valueOf(state.defaultWeights[i]);
        state.rows.forEach(row -> row.set(row.defaults));
        rebuildWidgets();
    }

    private void save() {
        List<RandomizerSavePacket.SourceHeader> headers = new ArrayList<>();
        List<RandomizerSavePacket.Change> changes = new ArrayList<>();
        for (RandomizerSource s : RandomizerSource.values()) {
            SourceState state = states.get(s);
            int[] weights = new int[CATEGORIES.length];
            for (int i = 0; i < weights.length; i++) weights[i] = parse(state.weights[i], 0);
            headers.add(new RandomizerSavePacket.SourceHeader(state.enabled, weights));
            for (RowState row : state.rows) {
                Rule rule = row.rule();
                if (!rule.equals(row.defaults)) changes.add(new RandomizerSavePacket.Change(s, row.info.id, rule));
            }
        }
        for (RandomizerSavePacket packet : RandomizerSavePacket.split(headers, changes)) {
            ModNetwork.CHANNEL.sendToServer(packet);
        }
        onClose();
    }

    /** The chance of one roll giving this item: category share × item share inside the category. */
    private double chance(RowState row) {
        SourceState state = state();
        if (!state.enabled || !row.enabled) return 0;
        int weight = parse(row.weight, 0);
        int index = row.info.category.ordinal();
        if (weight <= 0 || categoryTotals[index] <= 0 || categoryWeightTotal <= 0) return 0;
        return 100.0 * parse(state.weights[index], 0) / categoryWeightTotal * weight / categoryTotals[index];
    }

    private void recomputeTotals() {
        SourceState state = state();
        Arrays.fill(categoryTotals, 0);
        for (RowState row : state.rows) {
            int weight = parse(row.weight, 0);
            if (row.enabled && weight > 0) categoryTotals[row.info.category.ordinal()] += weight;
        }
        categoryWeightTotal = 0;
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (categoryTotals[i] > 0) categoryWeightTotal += parse(state.weights[i], 0);
        }
    }

    private static String formatChance(double chance) {
        if (chance <= 0) return "-";
        if (chance < 0.01) return "<0.01%";
        return String.format(Locale.ROOT, chance >= 1 ? "%.1f%%" : "%.2f%%", chance);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= HEADER_Y - 2 && mouseY < HEADER_Y + 10) {
            Column column = columnAt(mouseX);
            if (column != null) {
                if (sortColumn == column) {
                    descending = !descending;
                } else {
                    sortColumn = column;
                    descending = column != Column.ITEM; // names A-Z, numbers highest first
                }
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                rebuildList();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Nullable
    private Column columnAt(double mouseX) {
        int rowLeft = list.getRowLeft();
        for (Column column : Column.values()) {
            if (mouseX >= rowLeft + column.x && mouseX < rowLeft + column.x + column.width) return column;
        }
        return null;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        recomputeTotals();
        super.render(graphics, mouseX, mouseY, partialTick);

        int center = width / 2;
        graphics.drawCenteredString(font, title, center, 6, 0xFFFFFF);
        if (filter.category() != null) {
            Component label = Component.translatable(KEY + "category_weight");
            graphics.drawString(font, label, center + 146 - font.width(label), 46, 0xA0A0A0);
        }

        int rowLeft = list.getRowLeft();
        Column hovered = mouseY >= HEADER_Y - 2 && mouseY < HEADER_Y + 10 ? columnAt(mouseX) : null;
        for (Column column : Column.values()) {
            String arrow = column == sortColumn ? (descending ? " v" : " ^") : "";
            int color = column == hovered ? 0xFFFFA0 : column == sortColumn ? 0xFFFFFF : 0xA0A0A0;
            graphics.drawString(font, Component.translatable(KEY + column.key).append(arrow), rowLeft + column.x, HEADER_Y, color);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (rebuildPending) {
            rebuildPending = false;
            rebuildWidgets();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class RuleList extends ContainerObjectSelectionList<RuleEntry> {
        RuleList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
        }

        void setRows(List<RuleEntry> entries) {
            replaceEntries(entries);
            setScrollAmount(0);
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

    private final class RuleEntry extends ContainerObjectSelectionList.Entry<RuleEntry> {
        private final RowState row;
        private final ChoiceButton<Boolean> toggle;
        private final EditBox weight;
        private final EditBox min;
        private final EditBox max;
        private final Button reset;
        private final List<AbstractWidget> widgets;

        RuleEntry(RowState row) {
            this.row = row;
            toggle = ChoiceButton.onOff(0, 0, 28, 18, null, row.enabled, value -> row.enabled = value);
            weight = numberBox(0, 0, 32, 4, row.weight, text -> row.weight = text);
            weight.setTooltip(Tooltip.create(Component.translatable(KEY + "weight.tooltip")));
            min = numberBox(0, 0, 22, 2, row.min, text -> row.min = text);
            max = numberBox(0, 0, 22, 2, row.max, text -> row.max = text);
            reset = Button.builder(Component.literal("R"), b -> {
                row.set(row.defaults);
                toggle.setValue(row.enabled);
                weight.setValue(row.weight);
                min.setValue(row.min);
                max.setValue(row.max);
            }).bounds(0, 0, 20, 18).tooltip(Tooltip.create(Component.translatable(KEY + "reset"))).build();
            widgets = List.of(toggle, weight, min, max, reset);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            graphics.renderItem(row.info.stack, left, top + 1);
            boolean modified = !row.rule().equals(row.defaults);
            int nameColor = !row.enabled ? 0x707070 : modified ? 0xFFE080 : 0xFFFFFF;
            graphics.drawString(font, font.plainSubstrByWidth(row.info.name, 126), left + 20, top + 5, nameColor);
            double chance = chance(row);
            graphics.drawString(font, formatChance(chance), left + 150, top + 5, chance > 0 ? 0x90E090 : 0x707070);

            place(toggle, left + 204, top);
            place(weight, left + 236, top + 1);
            place(min, left + 275, top + 1);
            place(max, left + 303, top + 1);
            place(reset, left + 334, top);
            for (AbstractWidget widget : widgets) widget.render(graphics, mouseX, mouseY, partialTick);
        }

        private void place(AbstractWidget widget, int x, int y) {
            widget.setX(x);
            widget.setY(y);
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
