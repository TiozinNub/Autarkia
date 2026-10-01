package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.UUID;

/**
 * A party's building, from the moment its site is chosen: the first form of the builder's record
 * (docs/superpowers/specs/2026-10-01-house-site-design.md, *After the choice*). It names the plan by
 * its blueprint, version, variants and bindings, which plan the same building again; the plan copy
 * itself waits for the build project.
 *
 * @param anchor where the footprint's centre sits on layer 0, at the height the pad is levelled to
 * @param built  the bounds of its walls, in the world
 * @param pad    the ground levelled for it
 * @param note   why it was refused, or empty
 * @param work   who built it and when
 */
public record Structure(UUID id, String blueprint, int version, Map<String, String> variants,
                        Map<Integer, String> bindings, Pos anchor, Placement placement, Footprint built,
                        Footprint pad, Phase phase, long sitedAt, String note, Work work) {

    /**
     * The building's making: the members who placed any of it, and the game times its build was
     * posted and finished, -1 until then. A game day is 24000 of them.
     */
    public record Work(List<AgentId> builders, long begunAt, long builtAt) {
        public static final Work NONE = new Work(List.of(), -1, -1);

        public Work {
            builders = List.copyOf(builders);
        }
    }

    public enum Phase {
        /** Chosen and its ground claimed; waiting for its chunks to be cleared. */
        SITED,
        /** The pad's flatten is on the board. */
        LEVELLING,
        /** The pad is level; waiting for the builder. */
        LEVELLED,
        /** Its build is on the board. */
        BUILDING,
        /** It stands. */
        BUILT,
        /** The pad cannot be levelled; {@link #note} says why. */
        REFUSED
    }

    /** How far past the pad a flatten eases the ground: its chunks must be cleared too. */
    public static final int RING = dev.luizloyola.autarkia.core.earthwork.FlattenPlan.Rules.MAX_RING;

    public Structure {
        Objects.requireNonNull(id, "id");
        variants = Map.copyOf(variants);
        bindings = Map.copyOf(bindings);
        note = note == null ? "" : note;
        work = work == null ? Work.NONE : work;
    }

    public Structure(UUID id, String blueprint, int version, Map<String, String> variants,
                     Map<Integer, String> bindings, Pos anchor, Placement placement, Footprint built, Footprint pad,
                     Phase phase, long sitedAt, String note) {
        this(id, blueprint, version, variants, bindings, anchor, placement, built, pad, phase, sitedAt, note, Work.NONE);
    }

    public Structure at(Phase next, String why) {
        return new Structure(id, blueprint, version, variants, bindings, anchor, placement, built, pad, next, sitedAt,
                why, work);
    }

    public Structure with(Work next) {
        return new Structure(id, blueprint, version, variants, bindings, anchor, placement, built, pad, phase, sitedAt,
                note, next);
    }

    /** The chunks that must be cleared before the pad is levelled: the pad and the ring round it. */
    public SortedSet<ChunkKey> groundChunks() {
        return ChunkKey.covering(ChunkKey.OVERWORLD, pad.minX() - RING, pad.minZ() - RING, pad.maxX() + RING,
                pad.maxZ() + RING);
    }
}
