package com.bedwarsrandomizer.drop;

import java.util.Locale;

/** A roll first picks a category (by its weight), then an item inside it. */
public enum DropCategory {
    BLOCK, WEAPON, TOOL, ARMOR, ITEM, FOOD, POTION, ENCHANTMENT;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
