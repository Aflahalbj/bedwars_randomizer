package com.bedwarsrandomizer.enchant;

import com.bedwarsrandomizer.BedwarsRandomizer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.ItemStackedOnOtherEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pick up an enchanted book and click a tool, weapon or armor piece with it: the enchantments that fit are applied
 * right away and the book is used up. Books that don't fit anything behave like normal items.
 */
@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID)
public final class EnchantBookEvents {

    @SubscribeEvent
    public static void onStackedOnOther(ItemStackedOnOtherEvent event) {
        // Forge 1.20.1 passes the slot's item as "carried" and the cursor's item as "stacked on" (see AbstractContainerMenu#doClick)
        ItemStack book = event.getStackedOnItem();
        ItemStack target = event.getCarriedItem();
        Player player = event.getPlayer();
        if (!(book.getItem() instanceof EnchantedBookItem) || target.isEmpty() || target.getCount() != 1
                || target.getItem() instanceof EnchantedBookItem || !event.getSlot().mayPickup(player)) {
            return;
        }

        Map<Enchantment, Integer> stored = EnchantmentHelper.deserializeEnchantments(EnchantedBookItem.getEnchantments(book));
        Map<Enchantment, Integer> current = new LinkedHashMap<>(EnchantmentHelper.getEnchantments(target));
        boolean changed = false;
        for (Map.Entry<Enchantment, Integer> entry : stored.entrySet()) {
            Enchantment enchantment = entry.getKey();
            int level = entry.getValue();
            if (!enchantment.canEnchant(target)) continue;
            Integer existing = current.get(enchantment);
            if (existing != null) {
                if (existing < level) {
                    current.put(enchantment, level);
                    changed = true;
                }
            } else if (EnchantmentHelper.isEnchantmentCompatible(current.keySet(), enchantment)) {
                current.put(enchantment, level);
                changed = true;
            }
        }
        if (!changed) return;

        EnchantmentHelper.setEnchantments(current, target);
        event.getSlot().setChanged();
        book.shrink(1);
        if (!player.level().isClientSide) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        event.setCanceled(true);
    }

    private EnchantBookEvents() {}
}
