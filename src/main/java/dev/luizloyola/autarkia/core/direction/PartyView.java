package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
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
}
