package com.bedwarsrandomizer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** A button that steps through values: left click goes to the previous value, right click to the next. */
public class ChoiceButton<T> extends AbstractButton {
    private final List<T> values;
    private final Function<T, Component> label;
    @Nullable
    private final Component name;
    private final Consumer<T> onChange;
    private int index;

    public ChoiceButton(int x, int y, int width, int height, @Nullable Component name, List<T> values, T initial,
                        Function<T, Component> label, Consumer<T> onChange) {
        super(x, y, width, height, Component.empty());
        this.values = List.copyOf(values);
        this.label = label;
        this.name = name;
        this.onChange = onChange;
        this.index = Math.max(0, this.values.indexOf(initial));
        updateMessage();
    }

    public static ChoiceButton<Boolean> onOff(int x, int y, int width, int height, @Nullable Component name, boolean initial, Consumer<Boolean> onChange) {
        return new ChoiceButton<>(x, y, width, height, name, List.of(false, true), initial,
                value -> value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF, onChange);
    }

    public T value() {
        return values.get(index);
    }

    /** Shows another value without calling the change listener. */
    public void setValue(T value) {
        int found = values.indexOf(value);
        if (found >= 0) {
            index = found;
            updateMessage();
        }
    }

    /** Keyboard (Enter / Space): next value. */
    @Override
    public void onPress() {
        step(1);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || (button != 0 && button != 1) || !clicked(mouseX, mouseY)) return false;
        playDownSound(Minecraft.getInstance().getSoundManager());
        step(button == 0 ? -1 : 1);
        return true;
    }

    private void step(int direction) {
        index = Math.floorMod(index + direction, values.size());
        updateMessage();
        onChange.accept(values.get(index));
    }

    private void updateMessage() {
        Component value = label.apply(values.get(index));
        setMessage(name == null ? value : CommonComponents.optionNameValue(name, value));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
