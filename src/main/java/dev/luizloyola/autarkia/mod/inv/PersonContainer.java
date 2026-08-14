package dev.luizloyola.autarkia.mod.inv;

import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A live vanilla {@link Container} view over a Person's pure {@link Inventory}. Server-side only.
 *
 * <p><b>Write-through, not a snapshot.</b> Menu edits flush into the core inventory on
 * {@link #setChanged()}, which vanilla calls after every mutation, so there is no dupe window where
 * a taken item lives in both the player's inventory and an uncommitted snapshot.
 *
 * <p><b>Two-way, per slot</b>, since a menu stays open for minutes while the Person keeps living.
 * A slot whose core value moved away from {@code mirroredCore} was changed by the <em>Person</em>
 * and is re-pulled on the next read ({@link #pull}); a slot whose cache no longer matches
 * {@code mirroredVanilla} was changed by the <em>menu</em>, and only those are written back.
 * Without the pull the screen is frozen at open time; without the per-slot flush guard, a
 * {@code setChanged()} rewriting all 41 slots reverted everything the Person had done since the
 * window opened — spent durability restored, eaten food resurrected.
 *
 * <p>{@link #getItem} returns the cached stack <em>by reference</em>, as {@code SimpleContainer}
 * does, and vanilla's {@code moveItemStackTo} mutates it in place then calls {@link #setChanged()}
 * — hence {@link #pull} refuses to replace a slot the menu has dirtied.
 */
public final class PersonContainer implements Container {
    private final Person person;
    private final Inventory inventory;
    private final HolderLookup.Provider registries;

    /** The vanilla view the menu reads and mutates; index-aligned with the core inventory. */
    private final ItemStack[] cache = new ItemStack[Inventory.SIZE];
    /** The core stack each cache entry was translated from — the reference for "did the Person move it?". */
    private final dev.luizloyola.anima.core.inv.ItemStack[] mirroredCore =
            new dev.luizloyola.anima.core.inv.ItemStack[Inventory.SIZE];
    /**
     * A copy of each cache entry as last agreed — the reference for "did the menu move it?". A copy
     * rather than the stack itself precisely because vanilla mutates the cached stack in place.
     */
    private final ItemStack[] mirroredVanilla = new ItemStack[Inventory.SIZE];

    public PersonContainer(Person person) {
        this.person = person;
        this.inventory = person.inventory();
        this.registries = person.registryAccess();
        for (int slot = 0; slot < Inventory.SIZE; slot++) {
            adopt(slot, inventory.get(slot));
        }
    }

    /**
     * The Person's current food level ({@code 0..20}), for {@link PersonInventoryMenu}'s hunger
     * sync. Server-side only — the client menu uses a plain {@code SimpleContainer}, never this.
     */
    public int foodLevel() {
        return person.metabolism().foodLevel();
    }

    /**
     * The Person's selected hotbar slot ({@code 0..8}) — the one {@link Inventory#mainHand()} reads,
     * for {@link PersonInventoryMenu}'s selection sync. Server-side only, like {@link #foodLevel()}.
     */
    public int selectedSlot() {
        return inventory.selectedSlot();
    }

    // --- the two-way reconcile -------------------------------------------------------------------

    /** Translates {@code core} into the cache and records both sides as agreed for {@code slot}. */
    private void adopt(int slot, dev.luizloyola.anima.core.inv.ItemStack core) {
        this.cache[slot] = ItemStacks.toVanilla(core, registries);
        this.mirroredCore[slot] = core;
        this.mirroredVanilla[slot] = this.cache[slot].copy();
    }

    /**
     * Brings {@code slot}'s cache up to date with the core inventory if the Person moved it, before
     * every read and write — vanilla polls all 41 through {@link #getItem} once per tick from
     * {@code broadcastChanges}. An unchanged slot costs one record {@code equals}; only a moved one
     * pays the component translation. A slot the menu has dirtied is left alone: replacing it would
     * drop an edit vanilla is mid-way through making.
     */
    private void pull(int slot) {
        dev.luizloyola.anima.core.inv.ItemStack core = inventory.get(slot);
        if (core.equals(mirroredCore[slot])) return;
        if (!ItemStack.matches(cache[slot], mirroredVanilla[slot])) return;
        adopt(slot, core);
    }

    // --- Container -------------------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return Inventory.SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (int slot = 0; slot < Inventory.SIZE; slot++) {
            pull(slot);
            if (!cache[slot].isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        pull(slot);
        return cache[slot]; // by reference — see class doc
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        pull(slot);
        ItemStack removed = cache[slot].split(amount); // mutates cache[slot] to the remainder
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        pull(slot);
        ItemStack removed = cache[slot];
        cache[slot] = ItemStack.EMPTY;
        setChanged();
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        pull(slot); // so a write of what the cache already held still lands on a slot the Person moved
        cache[slot] = stack;
        setChanged();
    }

    /**
     * Flush the slots the <em>menu</em> changed back into the core inventory (the source of truth),
     * and only those — a slot the menu left alone belongs to the Person, who may well have moved it
     * since the window opened.
     */
    @Override
    public void setChanged() {
        for (int slot = 0; slot < Inventory.SIZE; slot++) {
            if (ItemStack.matches(cache[slot], mirroredVanilla[slot])) continue;
            inventory.set(slot, ItemStacks.toCore(cache[slot], registries));
            this.mirroredCore[slot] = inventory.get(slot); // read back: set() collapses empty
            this.mirroredVanilla[slot] = cache[slot].copy();
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
            pull(slot); // else a slot the Person filled since open looks unchanged and survives the flush
            cache[slot] = ItemStack.EMPTY;
        }
        setChanged();
    }
}
