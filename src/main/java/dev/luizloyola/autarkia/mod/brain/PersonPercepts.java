package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.compat.inv.CookedForms;
import dev.luizloyola.autarkia.compat.inv.FoodValues;
import dev.luizloyola.autarkia.core.brain.sense.FoodLookup;
import dev.luizloyola.autarkia.core.brain.sense.Percepts;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.FoodValue;
import dev.luizloyola.autarkia.core.person.Needs;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.Optional;

/**
 * The {@link Percepts} <em>adapter</em>: what a {@link Person}'s brain can sense, as version-neutral
 * views — the sensory twin of {@link PersonMover}/{@link PersonItemConsumer}. Thin by design: the
 * inventory and needs it exposes ARE the body's own core objects, so no copies can drift, and the
 * food lookup is a lens over {@link FoodValues}/{@link CookedForms} closing over this entity's
 * registry and server access — item knowledge on demand, never a snapshot.
 */
public final class PersonPercepts implements Percepts {
    private final Person person;
    /**
     * Food knowledge as a lens over live game data — vanilla and modded foods alike: values from
     * the item registry ({@link FoodValues}), cooked forms from the recipe data ({@link CookedForms}).
     */
    private final FoodLookup foods;

    public PersonPercepts(Person person) {
        this.person = person;
        this.foods = new FoodLookup() {
            @Override
            public Optional<FoodValue> of(ItemStack stack) {
                return FoodValues.of(stack, person.registryAccess());
            }

            @Override
            public Optional<FoodValue> cookedForm(ItemStack stack) {
                // Resolved per query, not captured: a Person only ever ticks server-side, and the
                // recipe view must be the CURRENT one (CookedForms re-keys its cache on /reload).
                return CookedForms.of(stack, person.level().getServer());
            }
        };
    }

    /** The carried inventory — the same core object the body mirrors and persists. */
    @Override
    public Inventory inventory() {
        return this.person.inventory();
    }

    /** The body's metabolism, read as pressure — the brain never writes here. */
    @Override
    public Needs needs() {
        return this.person.needs();
    }

    /** What any given stack is worth as food — see {@link FoodValues}. */
    @Override
    public FoodLookup foods() {
        return this.foods;
    }
}
