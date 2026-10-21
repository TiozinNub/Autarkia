package dev.luizloyola.autarkia.mod.person;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.social.GroupUp;
import dev.luizloyola.anima.core.social.GroupUps;
import dev.luizloyola.anima.core.social.Ledger;
import dev.luizloyola.anima.core.social.Ledgers;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.social.Places;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.store.Depot;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.social.ContactData;
import dev.luizloyola.anima.mod.social.EncounterData;
import dev.luizloyola.anima.mod.social.LedgerData;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.autarkia.core.board.ComeHome;
import dev.luizloyola.autarkia.core.board.FetchStation;
import dev.luizloyola.autarkia.core.person.Joining.Mover;
import dev.luizloyola.autarkia.core.person.Joining.Side;
import dev.luizloyola.autarkia.core.person.Joining;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * How settlers group up (group-up spec): only a homeless side moves ({@link Joining}); each side
 * decides by its party's ledgers as the speaker weighs them; and nobody asks again somebody who said
 * no within the encounter retention. A player decides by clicking, so a player's own meters are never
 * read.
 */
public final class PersonGroupUp implements GroupUp {

    /**
     * How hard a homeless settler willing to join goes looking for a party: over its own HOME search
     * (0.40), which walked a loner away from a settled pair in sight (flight, 2026-10-21); under
     * answering a call (0.55). It only ever targets somebody already perceived. A starting value.
     */
    static final double SEEKING = 0.45;

    /** Over the HOME search's 0.40 and the seek's 0.45: the first thing a newcomer takes. A starting value. */
    static final double COME_HOME_PRIORITY = 0.5;

    /** Under the walk home: a newcomer goes home first and back for its things after. A starting value. */
    static final double FETCH_PRIORITY = 0.45;

    /** Which refusal names the line that counts most against. */
    private static final Map<String, String> TOPICS = Map.of(
            LedgerData.NEWCOMER, "newcomers",
            LedgerData.MERGED, "merged",
            LedgerData.SIZE, "crowded",
            LedgerData.COMPANY, "crowded",
            PartyLines.FOOD, "food",
            PartyLines.HUNGRY, "food",
            PartyLines.BEDS, "beds",
            PartyLines.STUCK, "busy");

    private static volatile @Nullable MinecraftServer live;

    private PersonGroupUp() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            live = server;
            PartyLines.install(server);
            PartyData.get(server).onArrival((into, who) -> comeHome(server, into, who));
            PartyData.get(server).onMerge(merged -> farStations(server, merged));
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> live = null);
        GroupUps.install(new PersonGroupUp());
    }

    @Override
    public Optional<Ask> ask(AgentId self, AgentId other) {
        MinecraftServer server = live;
        if (server == null || together(server, self, other) || !introduced(server, self, other)
                || refusedLately(server, other, self)) {
            return Optional.empty();
        }
        return Joining.askFor(side(server, self), side(server, other)).filter(ask -> willing(server, self, ask));
    }

    @Override
    public double seeking(AgentId self) {
        MinecraftServer server = live;
        if (server == null || AgentBodies.findLoaded(server, self) == null) {
            return 0;
        }
        return side(server, self).homeless() && willing(server, self, Ask.JOIN) ? SEEKING : 0;
    }

    /** Somebody this homeless body could move in with, who has not turned it down lately. */
    @Override
    public boolean worthSeeking(AgentId self, AgentId other) {
        MinecraftServer server = live;
        if (server == null || together(server, self, other) || refusedLately(server, other, self)) {
            return false;
        }
        Mover mover = Joining.between(side(server, self), side(server, other));
        return mover == Mover.SELF || mover == Mover.EITHER;
    }

    @Override
    public Optional<String> refusal(AgentId self, AgentId asker, Ask ask) {
        MinecraftServer server = live;
        if (server == null) {
            return Optional.of("not_now");
        }
        Side theirs = side(server, asker);
        Side mine = side(server, self);
        if (!Joining.holds(theirs, mine, ask)) {
            return Optional.of(!theirs.homeless() && !mine.homeless() ? "settled" : "not_now");
        }
        // Asked to take somebody in, the party's openness answers; invited to move, its willingness.
        Ledger weighed = ask == Ask.JOIN ? Ledger.OPENNESS : Ledger.JOINING;
        Optional<Ledgers.Reading> reading = reading(server, self, weighed);
        if (reading.isEmpty() || reading.get().total() >= threshold(server, self, weighed)) {
            return Optional.empty();
        }
        return Optional.of(reading.get().strongestAgainst()
                .map(line -> TOPICS.getOrDefault(line.key(), "not_now")).orElse("not_now"));
    }

    @Override
    public Optional<Move> move(AgentId asker, AgentId asked, Ask ask) {
        MinecraftServer server = live;
        if (server == null || together(server, asker, asked)
                || !Joining.holds(side(server, asker), side(server, asked), ask)) {
            return Optional.empty();
        }
        PartyData parties = PartyData.get(server);
        PartyId askers = parties.partyOf(asker);
        PartyId askeds = parties.partyOf(asked);
        return Optional.of(ask == Ask.JOIN ? new Move(askers, askeds) : new Move(askeds, askers));
    }

    /**
     * Newcomers to a settled party walk home first (decision 20): those loaded and not already in its
     * area get one walk each on the party's board.
     */
    private static void comeHome(MinecraftServer server, PartyId into, List<AgentId> newcomers) {
        List<AgentId> walking = new ArrayList<>();
        Optional<Depot.Site> site = Optional.empty();
        for (AgentId who : newcomers) {
            AgentBody body = AgentBodies.findLoaded(server, who);
            site = site.isPresent() ? site : Depot.of(who);
            if (body != null && site.isPresent() && !site.get().area().contains(ChunkKey.at(ChunkKey.OVERWORLD,
                    body.entity().getBlockX(), body.entity().getBlockZ()))) {
                walking.add(who);
            }
        }
        if (!walking.isEmpty()) {
            PartyBoards.of(server, into).post(new ComeHome(site.get().hint(), walking, COME_HOME_PRIORITY));
            PartyBoards.touch(server);
        }
    }

    /**
     * What a joining party had claimed outside its new home's area (decision 21): a store is fetched
     * home by one of the newcomers and then let go; anything else is let go now.
     */
    private static void farStations(MinecraftServer server, PartyData.Merged merged) {
        Optional<Depot.Site> site = merged.members().stream().map(Depot::of).flatMap(Optional::stream).findFirst();
        if (site.isEmpty()) {
            return; // two homeless parties: nowhere is far from a home neither has
        }
        Places places = PlacesData.get(server).places();
        for (PlaceRow row : merged.places()) {
            if (site.get().holds(row.at())) {
                continue;
            }
            if (row.kind().equals(Store.POI)) {
                PartyBoards.of(server, merged.into()).post(new FetchStation(row.at(), merged.members(), FETCH_PRIORITY));
                PartyBoards.touch(server);
            } else {
                places.drop(row.kind(), row.at());
            }
        }
    }

    /** One side as {@link Joining} reads it; somebody in no party yet is a homeless party of one. */
    static Side side(MinecraftServer server, AgentId who) {
        PartyData parties = PartyData.get(server);
        Optional<PartyId> party = parties.currentPartyOf(who);
        if (party.isEmpty()) {
            return new Side(true, false, 1);
        }
        return new Side(PartyLines.homeless(server, party.get()), parties.leader(party.get()).isPresent(),
                parties.members(party.get()).size());
    }

    private static boolean together(MinecraftServer server, AgentId one, AgentId other) {
        PartyData parties = PartyData.get(server);
        Optional<PartyId> mine = parties.currentPartyOf(one);
        return mine.isPresent() && mine.equals(parties.currentPartyOf(other));
    }

    /** Names known both ways (decision 2). */
    private static boolean introduced(MinecraftServer server, AgentId one, AgentId other) {
        ContactData contacts = ContactData.get(server);
        return contacts.knows(one, other) && contacts.knows(other, one);
    }

    private static boolean refusedLately(MinecraftServer server, AgentId by, AgentId to) {
        long since = server.overworld().getGameTime() - Config.get().i(Knob.SOCIAL_ENCOUNTER_RETENTION_TICKS);
        return EncounterData.get(server).roster().saidLately(by, to, SpeechActs.REFUSE_JOIN.key(), since);
    }

    /**
     * Whether {@code self}'s party will make {@code ask}: asking to join reads its willingness to join,
     * inviting its openness. A player, with no nature to weigh it by, is always willing — the click
     * was the decision.
     */
    private static boolean willing(MinecraftServer server, AgentId self, Ask ask) {
        Ledger ledger = ask == Ask.JOIN ? Ledger.JOINING : Ledger.OPENNESS;
        Optional<Ledgers.Reading> reading = reading(server, self, ledger);
        return reading.isEmpty() || reading.get().total() >= threshold(server, self, ledger);
    }

    /** {@code ledger} for {@code self}'s party as self weighs it; empty for a player, who has no nature. */
    private static Optional<Ledgers.Reading> reading(MinecraftServer server, AgentId self, Ledger ledger) {
        AgentBody body = AgentBodies.findLoaded(server, self);
        if (body == null) {
            return Optional.empty();
        }
        return Optional.of(LedgerData.get(server).ledgers().read(ledger, PartyData.get(server).partyOf(self),
                body.profile(), LedgerData.now(server)));
    }

    private static double threshold(MinecraftServer server, AgentId self, Ledger ledger) {
        AgentBody body = AgentBodies.findLoaded(server, self);
        if (body == null) {
            return Double.NEGATIVE_INFINITY;
        }
        return body.profile().d(ledger == Ledger.JOINING
                ? ProfileAspect.SOCIAL_JOIN_THRESHOLD : ProfileAspect.SOCIAL_OPENNESS_THRESHOLD);
    }
}
