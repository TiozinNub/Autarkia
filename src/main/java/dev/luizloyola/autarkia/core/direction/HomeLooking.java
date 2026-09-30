package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Judgement;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Want;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a scout's stop needs from the world: the ground read around it, and a claim. Installed by
 * the mod layer; the search itself ({@link HomeSearch}) decides from what this hands back.
 */
public interface HomeLooking {

    /**
     * The plots around {@code at} as a member of {@code party} knowing what {@code knowledge} knows
     * would judge them, and the eight ways on from there. Empty when the world cannot be read.
     */
    Optional<Look> look(Pos at, PartyId party, AgentKnowledge knowledge);

    /**
     * Claims {@code plot} as the party's HOME if the ground, read again from where the claimant
     * stands, still allows it and nothing refuses it. Whether it did.
     */
    boolean claim(PartyId party, Candidate plot, AgentKnowledge knowledge);

    /**
     * What one stop saw.
     *
     * @param ways the eight headings of {@link HomeSearch#HEADINGS}, in order
     */
    record Look(Judgement judgement, List<Way> ways) {
        public Look {
            if (ways.size() != HomeSearch.HEADINGS) {
                throw new IllegalArgumentException(ways.size() + " ways, not " + HomeSearch.HEADINGS);
            }
            ways = List.copyOf(ways);
        }
    }

    /**
     * One heading from a stop.
     *
     * @param openLand the share of dry, flat ground in its sector 40 to 64 out, of the ground known
     * @param legEnd   a standable column near a leg's length that way, or null when there is none
     * @param ahead    the wants with an instance in its sector more than 32 out — the water and
     *                 lava the ground shows, the stone and nests the settler knows
     */
    record Way(double openLand, @Nullable Pos legEnd, Set<Want> ahead) {
        public Way {
            ahead = Set.copyOf(ahead);
        }
    }
}
