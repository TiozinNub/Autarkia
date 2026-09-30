package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.core.board.SetUp;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What a Direction may know of its party, and nothing more. <b>Never omniscient</b>: its claimed
 * places, what is inside its own stores, and its own projects' ledgers — never terrain nobody
 * reported.
 */
public interface PartyView {

    PartyId party();

    Optional<Home> home();

    int members();

    /**
     * How many items the spec names are in HOME's stores — the party's own chests on the plot or at
     * its yard. Empty when one of them could not be read this beat, or there is no HOME.
     */
    OptionalInt storedAtHome(ItemSpec spec);

    /**
     * How many hunger points of food, raw or ready, HOME's stores hold between them — what the food is
     * worth eaten, not how many items. Empty when one of them could not be read this beat, or there
     * is no HOME.
     */
    OptionalInt foodAtHome();

    /** Whether the party claims a place of this kind at HOME — on the plot or at its yard. */
    boolean hasAtHome(PoiKind kind);

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
