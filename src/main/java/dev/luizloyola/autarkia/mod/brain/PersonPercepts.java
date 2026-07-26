package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.compat.inv.CookedForms;
import dev.luizloyola.autarkia.compat.inv.FoodValues;
import dev.luizloyola.autarkia.compat.sense.LevelProbe;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockProbe;
import dev.luizloyola.autarkia.core.brain.sense.Drop;
import dev.luizloyola.autarkia.core.brain.sense.FoodLookup;
import dev.luizloyola.autarkia.core.brain.sense.Peer;
import dev.luizloyola.autarkia.core.brain.sense.Percepts;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.brain.sense.Threat;
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
import net.minecraft.world.entity.monster.Monster;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Percepts} <em>adapter</em>: what a {@link Person}'s brain can sense, as version-neutral
 * views — the sensory twin of {@link PersonMover}/{@link PersonItemConsumer}. Thin by design: the
 * inventory and needs it exposes ARE the body's own core objects, so no copies can drift, and the
 * food lookup is a lens over {@link FoodValues}/{@link CookedForms} closing over this entity's
 * registry and server access — item knowledge on demand, never a snapshot.
 */
public final class PersonPercepts implements Percepts {
    /**
     * Threat-percept budget window, in ticks. {@link #threats()} is an AABB entity query rather
     * than a field read, so its result is held this long and re-run only when stale.
     */
    private static final int CACHE_TICKS = 5;

    private final Person person;
    /**
     * Food knowledge as a lens over live game data — vanilla and modded foods alike: values from
     * the item registry ({@link FoodValues}), cooked forms from the recipe data ({@link CookedForms}).
     */
    private final FoodLookup foods;

    /** {@code person.tickCount} at which {@link #threatsCache} was last filled (see {@link #CACHE_TICKS}). */
    private int threatsQueriedAt;
    /** Last threat scan, reused within the budget window; {@code null} until the first query. */
    private @Nullable List<Threat> threatsCache;
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

    /**
     * Nearby hostiles as version-neutral {@link Threat}s — what the Flee instinct reads. Budgeted:
     * the scan runs at most once per {@link #CACHE_TICKS} ticks (the brain and every instinct may
     * ask each tick) and the same immutable list is returned in between. A hostile is any
     * {@link Monster} in a 16×8×16-inflated box, matching what the aggro slice attacks; broader
     * categories (the {@code Enemy} marker) cannot be asked of {@code getEntitiesOfClass} and
     * aren't worth a Mob-wide scan plus filter yet.
     */
    @Override
    public List<Threat> threats() {
        int now = this.person.tickCount;
        if (this.threatsCache != null && now - this.threatsQueriedAt < CACHE_TICKS) {
            return this.threatsCache;
        }
        List<Monster> mobs = this.person.level().getEntitiesOfClass(
                Monster.class, this.person.getBoundingBox().inflate(16.0, 8.0, 16.0));
        List<Threat> threats = new ArrayList<>(mobs.size());
        for (Monster mob : mobs) {
            if (!mob.isAlive()) {
                continue;
            }
            BlockPos at = mob.blockPosition();
            threats.add(new Threat(
                    new Pos(at.getX(), at.getY(), at.getZ()),
                    mob.distanceTo(this.person),
                    mob.getTarget() == this.person));
        }
        this.threatsQueriedAt = now;
        this.threatsCache = List.copyOf(threats);
        return this.threatsCache;
    }

    /** The world's blocks through the one {@link BlockProbe} vocabulary — the task-time re-walk sense. */
    @Override
    public BlockProbe blocks() {
        return this.blocks;
    }

    /**
     * Nearby dropped items as bare sightings — budgeted exactly like {@link #threats()}, over the
     * same 16×8×16 box. Consumers filter to their own work areas.
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
     * Nearby people — other {@link Person}s and live players, one seamless list (see
     * {@link Peer}). A read of the body's {@link PeerSense} state, not a scan: the cone, line of
     * sight, ears, cadences and linger window all live in the sensor.
     */
    @Override
    public List<Peer> peers() {
        return this.person.peerSense().peers();
    }

    /** The overworld game clock — the same one knowledge timestamps carry. */
    @Override
    public long time() {
        return this.person.level().getGameTime();
    }
}
