package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.compat.inv.CookedForms;
import dev.luizloyola.autarkia.compat.inv.FoodValues;
import dev.luizloyola.autarkia.compat.sense.LevelProbe;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockProbe;
import dev.luizloyola.autarkia.core.brain.sense.Being;
import dev.luizloyola.autarkia.core.brain.sense.Drop;
import dev.luizloyola.autarkia.core.brain.sense.FoodLookup;
import dev.luizloyola.autarkia.core.brain.sense.Percepts;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.FoodValue;
import dev.luizloyola.autarkia.core.person.Needs;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Percepts} <em>adapter</em>: what a {@link Person}'s brain can sense, as version-neutral
 * views. Thin — the inventory and needs it exposes ARE the body's own core objects, so
 * no copy can drift, and the food lookup is a lens over {@link FoodValues}/{@link CookedForms} bound
 * to this entity's registry and server, never a snapshot. The being sense ({@link #beings()})
 * subsumed the old omniscient threat scan.
 */
public final class PersonPercepts implements Percepts {
    /**
     * Drop-percept budget window, in ticks: an AABB entity query, not a plain field read, so
     * its result is held for this many ticks and re-run only when stale.
     */
    private static final int CACHE_TICKS = 5;

    private final Person person;
    /**
     * Food knowledge as a lens over live game data — vanilla and modded foods alike: values from
     * the item registry ({@link FoodValues}), cooked forms from the recipe data ({@link CookedForms}).
     */
    private final FoodLookup foods;

    /** The block sense — one {@link LevelProbe} over this person's level and eyes, shared with
     *  nothing (the sensor builds its own): stateless views are cheap, aliasing is not. */
    private final LevelProbe blocks;
    /** {@code person.tickCount} at which {@link #dropsCache} was last filled. */
    private int dropsQueriedAt;
    /** Last drop scan, reused within the budget window; {@code null} until the first query. */
    private @Nullable List<Drop> dropsCache;

    public PersonPercepts(Person person) {
        this.person = person;
        this.blocks = new LevelProbe(person);
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

    /** Where the body actually stands, in whole blocks. */
    @Override
    public Pos position() {
        BlockPos pos = this.person.blockPosition();
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The world's blocks through the one {@link BlockProbe} vocabulary — the task-time re-walk sense. */
    @Override
    public BlockProbe blocks() {
        return this.blocks;
    }

    /**
     * Nearby dropped items as bare sightings, budgeted: the entity query runs at most once per
     * {@link #CACHE_TICKS} ticks, the same immutable list in between. The 16×8×16 box matches the
     * threat sense; consumers filter to their own work areas.
     */
    @Override
    public List<Drop> drops() {
        int now = this.person.tickCount;
        if (this.dropsCache != null && now - this.dropsQueriedAt < CACHE_TICKS) {
            return this.dropsCache;
        }
        List<ItemEntity> items = this.person.level().getEntitiesOfClass(
                ItemEntity.class, this.person.getBoundingBox().inflate(16.0, 8.0, 16.0));
        List<Drop> drops = new ArrayList<>(items.size());
        for (ItemEntity item : items) {
            if (!item.isAlive()) {
                continue;
            }
            BlockPos at = item.blockPosition();
            drops.add(new Drop(new Pos(at.getX(), at.getY(), at.getZ()),
                    BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString()));
        }
        this.dropsQueriedAt = now;
        this.dropsCache = List.copyOf(drops);
        return this.dropsCache;
    }

    /**
     * Everything they currently perceive — a read of the body's {@link BeingSense} state, not a
     * scan: the sight cone, the ears on the vibration bus, attention cadences, the identification
     * ladder, herds and the linger window all live in the sensor. {@code peers()} stays the
     * person-filtered view via the interface default.
     */
    @Override
    public List<Being> beings() {
        return this.person.beingSense().beings();
    }

    /** The overworld game clock — the same one knowledge timestamps carry. */
    @Override
    public long time() {
        return this.person.level().getGameTime();
    }
}
