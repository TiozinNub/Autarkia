package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockProbe;
import dev.luizloyola.autarkia.core.brain.knowledge.FakeProbe;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test double for the {@link Percepts} bundle: a real {@link Inventory} and {@link Needs} —
 * already pure and headless, so faking them would fake away the truth — plus a map-backed
 * {@link FoodLookup} for compat's registry+recipe read. Nothing is cookable by default,
 * mirroring compat finding no bettering recipe.
 */
final class FakePercepts implements Percepts {
    final Inventory inventory = new Inventory();
    final Needs needs = new Needs();
    /** Her feet cell; the default is a surface stance so wander targets are sane. */
    Pos position = new Pos(0, 64, 0);
    List<Threat> threats = List.of();
    /** The block world — a real {@link FakeProbe} (flat ground at y 63, sparse blocks on top). */
    final FakeProbe blocks = new FakeProbe();
    List<Drop> drops = List.of();
    /** Nearby people — Persons and players alike. */
    List<Peer> peers = List.of();
    /** The game clock — settable; tests that price staleness advance it. */
    long time;
    private final Map<String, FoodValue> foodById = new HashMap<>();
    private final Map<String, FoodValue> cookedById = new HashMap<>();

    /** Register item {@code id} as edible with the given value — the test's food registry. */
    void food(String id, FoodValue value) {
        foodById.put(id, value);
    }

    /** Register {@code id}'s strictly-better one-step cooked form — the test's recipe book. */
    void cooked(String id, FoodValue cookedValue) {
        cookedById.put(id, cookedValue);
    }

    @Override
    public Inventory inventory() {
        return inventory;
    }

    @Override
    public Needs needs() {
        return needs;
    }

    @Override
    public Pos position() {
        return position;
    }

    @Override
    public List<Threat> threats() {
        return threats;
    }

    @Override
    public BlockProbe blocks() {
        return blocks;
    }

    @Override
    public List<Drop> drops() {
        return drops;
    }

    @Override
    public List<Peer> peers() {
        return peers;
    }

    @Override
    public long time() {
        return time;
    }

    @Override
    public FoodLookup foods() {
        // An empty stack's id is "" and never registered, so it reads as inedible (and
        // uncookable) for free.
        return new FoodLookup() {
            @Override
            public Optional<FoodValue> of(ItemStack stack) {
                return Optional.ofNullable(foodById.get(stack.id()));
            }

            @Override
            public Optional<FoodValue> cookedForm(ItemStack stack) {
                return Optional.ofNullable(cookedById.get(stack.id()));
            }
        };
    }
}
