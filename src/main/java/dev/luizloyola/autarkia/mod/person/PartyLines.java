package dev.luizloyola.autarkia.mod.person;

import dev.luizloyola.anima.compat.inv.FoodValues;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.agent.need.NeedLevel;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.social.Ledger;
import dev.luizloyola.anima.core.social.Ledgers;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.social.LedgerData;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.autarkia.compat.inv.StoreContents;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.mod.builder.StructuresData;
import dev.luizloyola.autarkia.mod.direction.DirectionsData;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.entity.Persons;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * What a settler's party weighs beside Anima's own lines (group-up spec, decisions 8–9): food to
 * spare, hungry members, beds once a building sleeps people, stuck lines; and, for joining, having
 * no home. Every weight is a starting value, to be tuned on the forest.
 */
public final class PartyLines {

    public static final String FOOD = "autarkia.ledger.food";
    public static final String HUNGRY = "autarkia.ledger.hungry";
    public static final String BEDS = "autarkia.ledger.beds";
    public static final String STUCK = "autarkia.ledger.stuck";
    public static final String HOMELESS = "autarkia.ledger.homeless";

    /** Per full bar of food each member could have beyond their own, up to {@link #FOOD_MOST}. */
    private static final double FOOD_PER_BAR = 5.0;
    private static final double FOOD_MOST = 15.0;
    /** All loaded members peckish or worse. */
    private static final double HUNGRY_ALL = 10.0;
    private static final double BED_FREE = 5.0;
    private static final int BEDS_COUNTED = 3;
    private static final double NO_BED = -10.0;
    private static final double PER_STUCK_LINE = -5.0;
    private static final double STUCK_MOST = -15.0;
    private static final double NO_HOME = 10.0;
    /** A party left with no home by a leader who chose none. */
    private static final double NO_HOME_DISTRESS = 5.0;
    /** Hungry members make a party keener to join someone, as much as they make it less open. */
    private static final double HUNGRY_JOIN = HUNGRY_ALL;
    private static final int BAR = 20;

    private PartyLines() {
    }

    /** Once per server start, after Anima's ledger store is installed. */
    public static void install(MinecraftServer server) {
        Ledgers ledgers = LedgerData.get(server).ledgers();
        ledgers.stands(line(Ledger.OPENNESS, FOOD, (party, reader) -> spareFood(server, party)));
        ledgers.stands(line(Ledger.OPENNESS, HUNGRY, (party, reader) -> hungry(server, party, -HUNGRY_ALL)));
        ledgers.stands(line(Ledger.OPENNESS, BEDS, (party, reader) -> beds(server, party)));
        ledgers.stands(line(Ledger.OPENNESS, STUCK, (party, reader) -> stuck(server, party)));
        ledgers.stands(line(Ledger.JOINING, HOMELESS, (party, reader) ->
                homeless(server, party) ? OptionalDouble.of(NO_HOME) : OptionalDouble.empty()));
        ledgers.stands(line(Ledger.JOINING, HUNGRY, (party, reader) -> hungry(server, party, HUNGRY_JOIN)));
        // Distress, for the discontent with an absent leader (decision 17): what the party suffers.
        ledgers.stands(line(Ledger.DISCONTENT, HUNGRY, (party, reader) -> hungry(server, party, HUNGRY_ALL)));
        ledgers.stands(line(Ledger.DISCONTENT, STUCK, (party, reader) -> {
            OptionalDouble stuck = stuck(server, party);
            return stuck.isPresent() ? OptionalDouble.of(-stuck.getAsDouble()) : stuck;
        }));
        ledgers.stands(line(Ledger.DISCONTENT, HOMELESS, (party, reader) ->
                homeless(server, party) ? OptionalDouble.of(NO_HOME_DISTRESS) : OptionalDouble.empty()));
    }

    static boolean homeless(MinecraftServer server, PartyId party) {
        return DirectionsData.get(server).find(party).map(PartyProgress::home).isEmpty();
    }

    /** Full bars of food per member beyond the first, from the stores at HOME and the loaded packs. */
    private static OptionalDouble spareFood(MinecraftServer server, PartyId party) {
        List<AgentId> members = PartyData.get(server).members(party);
        if (members.isEmpty()) {
            return OptionalDouble.empty();
        }
        double bars = (double) foodPoints(server, party, members) / BAR / members.size() - 1.0;
        return OptionalDouble.of(Math.min(FOOD_MOST, Math.max(0.0, bars) * FOOD_PER_BAR));
    }

    private static int foodPoints(MinecraftServer server, PartyId party, List<AgentId> members) {
        int points = 0;
        Set<ChunkKey> area = new HashSet<>(Territories.of(server).area(party));
        Set<BlockPos> counted = new HashSet<>();
        for (PlaceRow row : PlacesData.get(server).places().rows()) {
            if (row.kind().equals(Store.POI) && party.equals(row.party())
                    && area.contains(ChunkKey.at(ChunkKey.OVERWORLD, row.at().x(), row.at().z()))) {
                points += StoreContents.read(server.overworld(), row.at(), id -> true, counted)
                        .map(StoreContents.Reading::nutrition).orElse(0);
            }
        }
        for (AgentId member : members) {
            Person person = Persons.findLoaded(server, member);
            if (person == null) {
                continue;
            }
            for (Inventory.Entry entry : person.inventory().occupied()) {
                points += FoodValues.of(entry.stack(), server.registryAccess())
                        .map(food -> food.nutrition() * entry.stack().count()).orElse(0);
            }
        }
        return points;
    }

    /** {@code all} times the share of loaded members peckish or worse; nothing with nobody loaded. */
    private static OptionalDouble hungry(MinecraftServer server, PartyId party, double all) {
        int loaded = 0;
        int hungry = 0;
        for (AgentId member : PartyData.get(server).members(party)) {
            AgentBody body = AgentBodies.findLoaded(server, member);
            if (body == null) {
                continue;
            }
            loaded++;
            Optional<String> level = body.needs().level(NeedKind.HUNGER).map(NeedLevel::key);
            if (level.isPresent() && !level.get().equals("sated")) {
                hungry++;
            }
        }
        return loaded == 0 ? OptionalDouble.empty() : OptionalDouble.of(all * hungry / loaded);
    }

    /** Silent until a standing building sleeps people (decision 8); then free beds, or none. */
    private static OptionalDouble beds(MinecraftServer server, PartyId party) {
        int beds = 0;
        for (Structure structure : StructuresData.get(server).of(party)) {
            if (structure.stands()) {
                beds += switch (structure.variants().getOrDefault("beds", "none")) {
                    case "one" -> 1;
                    case "two" -> 2;
                    default -> 0;
                };
            }
        }
        if (beds == 0) {
            return OptionalDouble.empty();
        }
        int free = beds - PartyData.get(server).members(party).size();
        return OptionalDouble.of(free > 0 ? BED_FREE * Math.min(free, BEDS_COUNTED) : NO_BED);
    }

    private static OptionalDouble stuck(MinecraftServer server, PartyId party) {
        return DirectionsData.get(server).find(party).map(progress -> {
            long lines = progress.stalls().values().stream().filter(PartyProgress.Stall::stuck).count();
            return OptionalDouble.of(Math.max(STUCK_MOST, lines * PER_STUCK_LINE));
        }).orElse(OptionalDouble.empty());
    }

    private static Ledgers.Standing line(Ledger ledger, String key,
            BiFunction<PartyId, AgentProfile, OptionalDouble> points) {
        return new Ledgers.Standing() {
            @Override
            public Ledger ledger() {
                return ledger;
            }

            @Override
            public String key() {
                return key;
            }

            @Override
            public OptionalDouble points(PartyId party, AgentProfile reader) {
                return points.apply(party, reader);
            }
        };
    }
}
