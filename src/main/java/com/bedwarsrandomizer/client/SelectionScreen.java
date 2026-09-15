package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.block.selection.SelectionBlock;
import com.bedwarsrandomizer.block.selection.SelectionBlockEntity;
import com.bedwarsrandomizer.network.ModNetwork;
import com.bedwarsrandomizer.network.SelectionUpdatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.world.item.DyeColor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SelectionScreen extends Screen {
    private static final int BOX_WIDTH = 50;
    private static final int GAP = 4;
    private static final String[] AXES = {"X", "Y", "Z"};

    private final BlockPos pos;
    private final Direction facing;
    /** offset x/y/z, then size x/y/z — kept as text so a resize doesn't lose half-typed values. */
    private final String[] values = new String[6];
    private boolean showBox;
    @Nullable
    private DyeColor team;

    public static void open(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof SelectionBlockEntity selection) {
            mc.setScreen(new SelectionScreen(selection));
        }
    }

    private SelectionScreen(SelectionBlockEntity selection) {
        super(Component.translatable("block.bedwarsrandomizer.selection_block"));
        this.pos = selection.getBlockPos();
        this.facing = selection.facing();
        SelectionBlockEntity.Settings settings = selection.settings();
        BlockPos offset = settings.offset();
        BlockPos size = settings.size();
        int[] numbers = {offset.getX(), offset.getY(), offset.getZ(), size.getX(), size.getY(), size.getZ()};
        for (int i = 0; i < 6; i++) values[i] = String.valueOf(numbers[i]);
        this.showBox = settings.showBox();
        this.team = settings.team();
    }

    @Override
    protected void init() {
        int left = width / 2 - (3 * BOX_WIDTH + 2 * GAP) / 2;
        for (int i = 0; i < 6; i++) {
            int index = i;
            int y = i < 3 ? 50 : 90;
            EditBox box = new EditBox(font, left + (i % 3) * (BOX_WIDTH + GAP), y, BOX_WIDTH, 20,
                    Component.literal((i < 3 ? "Offset " : "Size ") + AXES[i % 3]));
            box.setMaxLength(5);
            box.setFilter(text -> text.matches("-?\\d{0,4}"));
            box.setValue(values[i]);
            box.setResponder(text -> values[index] = text);
            addRenderableWidget(box);
        }

        int buttonsLeft = width / 2 - 154;
        List<Optional<DyeColor>> teams = new ArrayList<>();
        teams.add(Optional.empty());
        for (DyeColor color : DyeColor.values()) teams.add(Optional.of(color));
        ChoiceButton<Optional<DyeColor>> teamButton = new ChoiceButton<>(buttonsLeft + 158, 160, 150, 20,
                Component.translatable("gui.bedwarsrandomizer.selection.team"), teams, Optional.ofNullable(team),
                SelectionScreen::teamLabel, value -> team = value.orElse(null));
        teamButton.setTooltip(Tooltip.create(Component.translatable("gui.bedwarsrandomizer.selection.team.tooltip")));
        addRenderableWidget(teamButton);
        addRenderableWidget(ChoiceButton.onOff(buttonsLeft, 160, 150, 20,
                Component.translatable("gui.bedwarsrandomizer.selection.show_box"), showBox, value -> showBox = value));
        addRenderableWidget(Button.builder(Component.translatable("gui.bedwarsrandomizer.selection.save"), b -> send(true))
                .bounds(buttonsLeft, 136, 308, 20)
                .tooltip(Tooltip.create(Component.translatable("gui.bedwarsrandomizer.selection.save.tooltip")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> send(false))
                .bounds(buttonsLeft, 184, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(buttonsLeft + 158, 184, 150, 20).build());
    }

    private static Component teamLabel(Optional<DyeColor> team) {
        return team.map(SelectionBlock::teamName)
                .orElseGet(() -> Component.translatable("gui.bedwarsrandomizer.selection.team.none"));
    }

    private SelectionBlockEntity.Settings currentSettings() {
        return new SelectionBlockEntity.Settings(
                new BlockPos(number(0), number(1), number(2)),
                new BlockPos(number(3), number(4), number(5)),
                showBox, team);
    }

    private int number(int index) {
        try {
            return Integer.parseInt(values[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void send(boolean save) {
        ModNetwork.CHANNEL.sendToServer(new SelectionUpdatePacket(pos, currentSettings(), save));
        onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            send(false);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int center = width / 2;
        int left = center - (3 * BOX_WIDTH + 2 * GAP) / 2;
        graphics.drawCenteredString(font, title, center, 12, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("gui.bedwarsrandomizer.selection.facing",
                Component.translatable("gui.bedwarsrandomizer.direction." + facing.getName())), center, 24, 0xA0A0A0);
        graphics.drawString(font, Component.translatable("gui.bedwarsrandomizer.selection.offset"), left, 40, 0xA0A0A0);
        graphics.drawString(font, Component.translatable("gui.bedwarsrandomizer.selection.size"), left, 80, 0xA0A0A0);

        BoundingBox box = SelectionBlockEntity.worldBox(pos, facing, currentSettings());
        Component range = box == null
                ? Component.translatable("gui.bedwarsrandomizer.selection.empty")
                : Component.translatable("gui.bedwarsrandomizer.selection.range",
                box.minX(), box.maxX(), box.minY(), box.maxY(), box.minZ(), box.maxZ());
        graphics.drawCenteredString(font, range, center, 122, 0xE0E0E0);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
