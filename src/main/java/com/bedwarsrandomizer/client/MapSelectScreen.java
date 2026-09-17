package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.network.MapSelectPacket;
import com.bedwarsrandomizer.network.ModNetwork;
import com.bedwarsrandomizer.network.SelectMapPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Pick the arena map: from [Teleport all to lobby] everyone registered then goes to its lobby; from /bwr maps it just changes. */
public class MapSelectScreen extends Screen {
    private static final String KEY = "gui.bedwarsrandomizer.map.";
    private static final int PER_PAGE = 6;

    private final List<String> maps;
    private final String current;
    private final boolean teleportAll;
    private int page;

    public static void open(MapSelectPacket packet) {
        Minecraft.getInstance().setScreen(new MapSelectScreen(packet.maps(), packet.current(), packet.teleportAll()));
    }

    private MapSelectScreen(List<String> maps, String current, boolean teleportAll) {
        super(Component.translatable(KEY + "title"));
        this.maps = maps;
        this.current = current;
        this.teleportAll = teleportAll;
    }

    @Override
    protected void init() {
        int center = width / 2;
        int top = 40;
        int pages = Math.max(1, (maps.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);
        for (int i = page * PER_PAGE; i < Math.min(maps.size(), (page + 1) * PER_PAGE); i++) {
            String map = maps.get(i);
            Component label = map.equals(current) ? Component.translatable(KEY + "current", map) : Component.literal(map);
            addRenderableWidget(Button.builder(label, b -> {
                        ModNetwork.CHANNEL.sendToServer(new SelectMapPacket(map, teleportAll));
                        onClose();
                    })
                    .bounds(center - 100, top + (i - page * PER_PAGE) * 24, 200, 20)
                    .tooltip(Tooltip.create(Component.translatable(KEY + (teleportAll ? "tooltip" : "tooltip.change"))))
                    .build());
        }
        int bottom = height - 30;
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = (page + pages - 1) % pages;
                rebuildWidgets();
            }).bounds(center - 100, bottom - 26, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = (page + 1) % pages;
                rebuildWidgets();
            }).bounds(center + 80, bottom - 26, 20, 20).build());
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(center - 50, bottom, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable(KEY + (teleportAll ? "hint" : "hint.change")), width / 2, 26, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
