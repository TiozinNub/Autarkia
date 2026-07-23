package dev.luizloyola.autarkia.mod.item;

import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** Registers Autarkia's items. Call {@link #init()} from mod init. */
public final class ModItems {
    private ModItems() {}

    /**
     * Debug wand — a development tool for probing NPCs. A vanilla stick model
     * ({@code minecraft:item/stick}) with a forced enchantment glint so it stands out in the
     * inventory. Right-clicking a {@code Person} selects it (see {@link DebugWandItem}); the
     * selection is per-player in {@code PersonSelection}, not on the stack.
     */
    public static final Item DEBUG_WAND = register("debug_wand",
            props -> new DebugWandItem(props.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)));

    private static Item register(String name, Function<Item.Properties, Item> factory) {
        Identifier id = Identifier.fromNamespaceAndPath("autarkia", name);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        Item item = factory.apply(new Item.Properties().setId(key));
        return Registry.register(BuiltInRegistries.ITEM, id, item);
    }

    /** Triggers registration via class load. */
    public static void init() {}
}
