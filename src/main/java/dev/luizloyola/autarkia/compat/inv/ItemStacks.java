package dev.luizloyola.autarkia.compat.inv;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.Equippable;
import org.jspecify.annotations.Nullable;

/**
 * Translation between the pure {@link ItemStack core ItemStack} and a real {@code net.minecraft}
 * {@code ItemStack} — the item seam {@code core} may not touch.
 *
 * <p>The full item crosses losslessly: id, count, stack cap and every data component. The component
 * patch rides through {@code core} as an opaque SNBT string, re-applied on the way back.
 * Registry-aware because components reference dynamic registries like enchantments, hence the
 * {@link HolderLookup.Provider} arguments.
 */
public final class ItemStacks {
    private ItemStacks() {}

    /** Key wrapping the encoded patch in a holder compound, so SNBT (de)serialization sees a CompoundTag. */
    private static final String COMPONENTS_KEY = "c";

    /** A core stack describing {@code stack}: kind id + count + cap + components. Empty/air → {@link ItemStack#EMPTY}. */
    public static ItemStack toCore(net.minecraft.world.item.ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int max = stack.getOrDefault(DataComponents.MAX_STACK_SIZE, 1);
        String components = encodeComponents(stack.getComponentsPatch(), registries);
        return ItemStack.of(id, stack.getCount(), max, components);
    }

    /** A vanilla stack for {@code stack}, components re-applied; empty or an unknown id → {@code ItemStack.EMPTY}. */
    public static net.minecraft.world.item.ItemStack toVanilla(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return net.minecraft.world.item.ItemStack.EMPTY;
        Item item = itemOrNull(stack.id());
        if (item == null) return net.minecraft.world.item.ItemStack.EMPTY;
        net.minecraft.world.item.ItemStack vanilla = new net.minecraft.world.item.ItemStack(item, stack.count());
        if (!stack.components().isEmpty()) {
            vanilla.applyComponents(decodeComponents(stack.components(), registries));
        }
        return vanilla;
    }

    /**
     * The equipment slot this item naturally goes in, from its default {@code Equippable}
     * component — {@code null} if it isn't wearable or holdable gear. Item defaults only, so no
     * registry access or component payload is needed.
     */
    public static @Nullable EquipmentSlot equipmentSlotOf(ItemStack stack) {
        Item item = itemOrNull(stack.id());
        if (item == null) return null;
        Equippable equippable = new net.minecraft.world.item.ItemStack(item).get(DataComponents.EQUIPPABLE);
        return equippable == null ? null : equippable.slot();
    }

    /**
     * A core template stack from a command's {@code item} argument (count 1 — the real count is set
     * in the core layer). The 26.1 {@code createItemStack} takes just a count; older targets also
     * take a {@code validate} flag. Components from any {…} the command carried ride along via
     * {@link #toCore}.
     */
    public static ItemStack templateOf(ItemInput input, HolderLookup.Provider registries)
            throws CommandSyntaxException {
        //? if >=26.1 {
        return toCore(input.createItemStack(1), registries);
        //?} else {
        /*return toCore(input.createItemStack(1, true), registries);
        *///?}
    }

    /** The stack cap the game assigns item {@code id} — used to re-derive a loaded stack's cap. */
    public static int maxStackSize(String id) {
        Item item = itemOrNull(id);
        if (item == null) return 1;
        return new net.minecraft.world.item.ItemStack(item).getOrDefault(DataComponents.MAX_STACK_SIZE, 1);
    }

    // --- component (de)serialization -------------------------------------------------------------

    /** The item's component patch as opaque SNBT ({@code ""} when it has no non-default components). */
    private static String encodeComponents(DataComponentPatch patch, HolderLookup.Provider registries) {
        if (patch.isEmpty()) return "";
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return DataComponentPatch.CODEC.encodeStart(ops, patch).result()
                .map(tag -> {
                    CompoundTag holder = new CompoundTag();
                    holder.put(COMPONENTS_KEY, tag);
                    return NbtUtils.structureToSnbt(holder);
                })
                .orElse("");
    }

    /** Parses opaque component SNBT back to a patch; unparseable/foreign data degrades to no components. */
    private static DataComponentPatch decodeComponents(String snbt, HolderLookup.Provider registries) {
        if (snbt.isEmpty()) return DataComponentPatch.EMPTY;
        try {
            Tag tag = NbtUtils.snbtToStructure(snbt).get(COMPONENTS_KEY);
            if (tag == null) return DataComponentPatch.EMPTY;
            RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
            return DataComponentPatch.CODEC.parse(ops, tag).result().orElse(DataComponentPatch.EMPTY);
        } catch (CommandSyntaxException e) {
            return DataComponentPatch.EMPTY;
        }
    }

    /** Resolves an item by id, or {@code null} for a malformed or unregistered id. */
    private static @Nullable Item itemOrNull(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) return null;
        Item item = BuiltInRegistries.ITEM.getValue(key);
        // The item registry is defaulted (unknown → air); treat air as "not a real item".
        return item == net.minecraft.world.item.Items.AIR ? null : item;
    }
}
