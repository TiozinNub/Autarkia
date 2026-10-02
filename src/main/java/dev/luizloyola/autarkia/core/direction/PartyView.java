package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.board.SetUp;
import java.util.Collections;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SortedSet;

/**
 * What a Direction may know of its party, and nothing more. <b>Never omniscient</b>: its claimed
 * places, what is inside its own stores, and its own projects' ledgers — never terrain nobody
 * reported.
 */
public interface PartyView {

    PartyId party();

    Optional<Home> home();

    /**
     * The party's area in the overworld — the chunks it holds, which HOME is. Empty for a party that
     * holds none; a test's view that never asks about the area need not answer.
     */
    default SortedSet<ChunkKey> area() {
        return Collections.emptySortedSet();
    }

    /**
     * Where the party's next store or station goes: beside its stores at HOME when it has one, else
     * the middle of its area. Empty with no HOME, or no area.
     */
    default Optional<dev.luizloyola.anima.core.brain.sense.Pos> spot() {
        return Optional.empty();
    }

    /** The party's buildings, from the moment a site is chosen. None for a test view that never asks. */
    default java.util.List<dev.luizloyola.autarkia.core.builder.Structure> structures() {
        return java.util.List.of();
    }

    /**
     * Whether a building of the party's is about to go up or going up: its base is moved off the
     * site for the length of it, and nothing is set up again until the building replaces it.
     */
    default boolean building() {
        return structures().stream().anyMatch(s -> s.phase() == dev.luizloyola.autarkia.core.builder.Structure.Phase.LEVELLED
                || s.phase() == dev.luizloyola.autarkia.core.builder.Structure.Phase.BUILDING);
    }

    /**
     * The least growth of one of the party's standing buildings that would hold one more of this
     * station: the house's {@code base=lv2} for room, {@code base=lv3} for a furnace. Empty when no
     * building can, or a test view never asks.
     */
    default Optional<dev.luizloyola.autarkia.core.builder.Growth> growth(SetUp.Station station) {
        return Optional.empty();
    }

    /** Whether a place of this kind at HOME stands inside one of the party's standing buildings. */
    default boolean housed(PoiKind kind) {
        return false;
    }

    /** Whether one of the party's buildings is growing: what a growth adds is on its way. */
    default boolean growing() {
        return structures().stream().anyMatch(s -> s.phase() == dev.luizloyola.autarkia.core.builder.Structure.Phase.GROWING);
    }

    int members();

    /**
     * How many items the spec names are in HOME's stores — the party's own chests in its area. Empty
     * when one of them could not be read this beat, or there is no HOME.
     */
    OptionalInt storedAtHome(ItemSpec spec);

    /**
     * {@link #storedAtHome}, counting only the stores a body could take it out of — those with a
     * side to stand at, by the take's own test. What work that fetches from HOME is posted on: a
     * cook was posted for a mutton in a walled-in chest and failed on it 336 times (forest,
     * 2026-10-02). All of it for a test view that never walls a store in.
     */
    default OptionalInt takeableAtHome(ItemSpec spec) {
        return storedAtHome(spec);
    }

    /**
     * How many hunger points of food, raw or ready, HOME's stores hold between them — what the food is
     * worth eaten, not how many items. Empty when one of them could not be read this beat, or there
     * is no HOME.
     */
    OptionalInt foodAtHome();

    /** Whether this Direction's work has got nowhere for a while — {@link PartyProgress.Stall#stuck}. */
    default boolean stuck(DirectionId direction) {
        return false;
    }

    /** Whether the party claims a place of this kind at HOME — in its area. */
    boolean hasAtHome(PoiKind kind);

    /** Where the party's place of this kind at HOME is, the first claimed if it has several. */
    default Optional<dev.luizloyola.anima.core.brain.sense.Pos> placeAtHome(PoiKind kind) {
        return Optional.empty();
    }

    /** Whether something is running at the party's place of this kind at HOME — a furnace smelting. */
    default boolean runningAtHome(PoiKind kind) {
        return false;
    }

    /**
     * How many empty slots HOME's stores have between them. Empty when one of them could not be
     * read this beat, or there is no HOME.
     */
    OptionalInt freeSlotsAtHome();

    /** Whether HOME has its base: a workbench and a store the party claims. */
    default boolean baseReady() {
        for (SetUp.Station station : BaseLine.STATIONS) {
            if (!hasAtHome(station.kind())) {
                return false;
            }
        }
        return true;
    }
}
