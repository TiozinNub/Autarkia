package dev.luizloyola.autarkia.mod.inv;

import dev.luizloyola.autarkia.compat.inv.ItemStacks;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A live vanilla {@link Container} view over a Person's core {@link Inventory}. Server-side only.
 *
 * <p><b>Write-through, not a snapshot.</b> Edits flush into the core inventory on
 * {@link #setChanged()}, which vanilla calls after every mutation — so there is no dupe window
 * where a taken item lives in both the player's inventory and an uncommitted copy.
 *
 * <p>Reads come from a per-slot vanilla-stack cache, so per-tick slot scans don't re-translate.
 * {@link #getItem} returns it <em>by reference</em> (as {@code SimpleContainer} does): vanilla's
 * {@code moveItemStackTo} mutates it in place, then {@link #setChanged()} writes the cache back
 * via {@link ItemStacks}. The {@link Person}'s equipment mirror reflects armor/hand changes onto
 * the entity next tick.
 */
public final class PersonContainer implements Container {
    private final Person person;
    private final Inventory inventory;
    private final HolderLookup.Provider registries;
    private final ItemStack[] cache = new ItemStack[Inventory.SIZE];

    public PersonContainer(Person person) {
        this.person = person;
        this.inventory = person.inventory();
        this.registries = person.registryAccess();
        for (int slot = 0; slot < Inventory.SIZE; slot++) {
            this.cache[slot] = ItemStacks.toVanilla(inventory.get(slot), registries);
        }
    }

    @Override
    public int getContainerSize() {
        return Inventory.SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : cache) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return cache[slot]; // by reference — see class doc
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = cache[slot].split(amount); // mutates cache[slot] to the remainder
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = cache[slot];
        cache[slot] = ItemStack.EMPTY;
        setChanged();
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        cache[slot] = stack;
        setChanged();
    }

    /** Flush the whole cache back into the core inventory (the source of truth). */
    @Override
    public void setChanged() {
        for (int slot = 0; slot < Inventory.SIZE; slot++) {
            inventory.set(slot, ItemStacks.toCore(cache[slot], registries));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        // A menu shouldn't stay open across the world.
        return person.isAlive() && person.distanceToSqr(player) <= 64.0;
    }

    @Override
    public void clearContent() {
        for (int slot = 0; slot < Inventory.SIZE; slot++) {
            cache[slot] = ItemStack.EMPTY;
        }
        setChanged();
    }
}
