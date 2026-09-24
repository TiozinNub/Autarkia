package dev.luizloyola.autarkia.mod.direction;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.autarkia.compat.inv.StoreContents;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.core.direction.PartyView;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * A party as its Directions may see it. HOME's stores are the party's own claimed chests on the plot
 * or near enough its yard to count — the same {@code stores.found_radius} a gather's own yard uses,
 * or a gather's chest just off the plot would never count toward the line that posted it.
 *
 * <p>Overworld only: a claimed place carries no dimension yet, and every settlement so far is there.
 */
final class HomeView implements PartyView {

    private final MinecraftServer server;
    private final PartyId party;
    private final PartyProgress progress;
    private final int members;

    HomeView(MinecraftServer server, PartyId party, PartyProgress progress, int members) {
        this.server = server;
        this.party = party;
        this.progress = progress;
        this.members = members;
    }

    @Override
    public PartyId party() {
        return party;
    }

    @Override
    public Optional<Home> home() {
        return Optional.ofNullable(progress.home());
    }

    @Override
    public int members() {
        return members;
    }

    @Override
    public OptionalInt storedAtHome(ItemSpec spec) {
        Home home = progress.home();
        if (home == null) {
            return OptionalInt.empty();
        }
        double radius = AutarkiaConfig.PERSON.i(ProfileAspect.STORES_FOUND_RADIUS);
        Set<BlockPos> counted = new HashSet<>();
        int total = 0;
        for (PlaceRow row : PlacesData.get(server).places().rows()) {
            if (!row.kind().equals(Store.POI) || !party.equals(row.party())) {
                continue;
            }
            if (!home.plot().contains(row.at()) && Store.distance(row.at(), home.yard()) > radius) {
                continue;
            }
            OptionalInt held = StoreContents.count(server.overworld(), row.at(), spec::matches, counted);
            if (held.isEmpty()) {
                return OptionalInt.empty();
            }
            total += held.getAsInt();
        }
        return OptionalInt.of(total);
    }
}
