package dev.luizloyola.autarkia.compat.inv;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import java.util.List;

/**
 * Persistence for the pure {@link Inventory}: the non-empty slots only (compact, like a vanilla
 * container), plus the selected hotbar slot. The per-stack cap is <em>re-derived</em> on decode
 * ({@link ItemStacks#maxStackSize}), never written, so it cannot go stale against a changed item.
 *
 * <p>In {@code compat}: codecs are DataFixerUpper, and deriving the cap is version-specific lookup.
 */
public final class Inventories {
    private Inventories() {}

    private record SlotData(int slot, String id, int count, String components) {}

    private static final Codec<SlotData> SLOT_CODEC = RecordCodecBuilder.create(s -> s.group(
            Codec.INT.fieldOf("slot").forGetter(SlotData::slot),
            Codec.STRING.fieldOf("id").forGetter(SlotData::id),
            Codec.INT.fieldOf("count").forGetter(SlotData::count),
            Codec.STRING.optionalFieldOf("components", "").forGetter(SlotData::components)
    ).apply(s, SlotData::new));

    public static final Codec<Inventory> CODEC = RecordCodecBuilder.create(inv -> inv.group(
            SLOT_CODEC.listOf().fieldOf("slots").forGetter(Inventories::toSlots),
            Codec.INT.optionalFieldOf("selected", 0).forGetter(Inventory::selectedSlot)
    ).apply(inv, Inventories::fromSlots));

    private static List<SlotData> toSlots(Inventory inv) {
        return inv.occupied().stream()
                .map(e -> new SlotData(e.slot(), e.stack().id(), e.stack().count(), e.stack().components()))
                .toList();
    }

    private static Inventory fromSlots(List<SlotData> slots, int selected) {
        Inventory inv = new Inventory();
        for (SlotData s : slots) {
            // Skip what a corrupt or older save could hold.
            if (s.slot() < 0 || s.slot() >= Inventory.SIZE || s.count() <= 0) continue;
            inv.set(s.slot(), ItemStack.of(s.id(), s.count(), ItemStacks.maxStackSize(s.id()), s.components()));
        }
        if (selected >= 0 && selected < Inventory.HOTBAR_SIZE) inv.setSelectedSlot(selected);
        return inv;
    }
}
