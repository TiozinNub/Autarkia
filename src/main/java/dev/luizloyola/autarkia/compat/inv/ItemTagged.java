package dev.luizloyola.autarkia.compat.inv;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

/** Item tags a spec in core matches on, read where the registry is. */
public final class ItemTagged {

    private ItemTagged() {
    }

    /** Whether an item is in {@code #minecraft:stone_crafting_materials} — what a furnace is made of. */
    public static boolean stoneCrafting(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        return id != null && new ItemStack(BuiltInRegistries.ITEM.getValue(id)).is(ItemTags.STONE_CRAFTING_MATERIALS);
    }
}
