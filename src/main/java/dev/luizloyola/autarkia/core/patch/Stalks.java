package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Optional;

/** The plants a settler cuts as they grow up (Luiz, 2026-10-03: one harvester for all of them). */
public final class Stalks {

    public static final ItemSpec CANE_ITEM =
            ItemSpec.register(new ItemSpec("sugar_cane", id -> id.equals("minecraft:sugar_cane")));
    public static final ItemSpec CACTUS_ITEM =
            ItemSpec.register(new ItemSpec("cactus", id -> id.equals("minecraft:cactus")));
    public static final ItemSpec BAMBOO_ITEM =
            ItemSpec.register(new ItemSpec("bamboo", id -> id.equals("minecraft:bamboo")));

    public static final Stalk CANE = new Stalk(Patches.CANE, Patches.SUGAR_CANE, CANE_ITEM, true, false);
    public static final Stalk CACTUS = new Stalk(Patches.CACTI, Patches.CACTUS, CACTUS_ITEM, true, true);
    public static final Stalk BAMBOO = new Stalk(Patches.BAMBOO, Patches.BAMBOO_STALK, BAMBOO_ITEM, true, false);

    public static final List<Stalk> ALL = List.of(CANE, CACTUS, BAMBOO);

    private Stalks() {
    }

    /** The stalk a patch of {@code kind} is made of, if it is one. */
    public static Optional<Stalk> of(PoiKind kind) {
        return ALL.stream().filter(stalk -> stalk.patch() == kind).findFirst();
    }
}
