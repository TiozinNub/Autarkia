package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.agent.AgentId;
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
     * {@code scout} took the search's step. A party that has no leader is led from now on by
     * whoever first scouts its search (decision: Luiz, 2026-10-21, group-up spec).
     */
    default void scouting(PartyId party, AgentId scout) {
    }

    /**
     * HOME on {@code plot} as it was found, without the second look a scout gives the plot it stands
     * on: a leader says yes from wherever they are, and the plot is often far and unloaded (client
     * run, 2026-10-21). Ground another party holds is still refused. Whether it was settled.
     */
    default boolean settleAsFound(PartyId party, Candidate plot) {
        return false;
    }

    /** The player who leads {@code party} and so decides its HOME, if one does (decisions 14–16). */
    default Optional<AgentId> waitingLeader(PartyId party) {
        return Optional.empty();
    }

    /**
     * Why the spot {@code at} will not do as {@code self}'s party's HOME — a {@code not_here} topic —
     * or empty when it passes, judged as a search judges a plot against the bar it has reached.
     */
    default Optional<String> judgeHere(AgentId self, Pos at, AgentKnowledge knowledge) {
        return Optional.of("poor");
    }

    /** The place {@code self}'s party found, to put to {@code leader} if that is who it waits on. */
    default Optional<Pos> suggestion(AgentId self, AgentId leader) {
        return Optional.empty();
    }

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
