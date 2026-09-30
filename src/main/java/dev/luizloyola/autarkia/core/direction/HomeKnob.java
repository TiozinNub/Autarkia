package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.config.KnobSpec;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;

/**
 * How a party judges a place to live: {@code home.*} in {@code config/autarkia.toml}. One table of
 * wants for the whole search, since HOME is chosen once, in the Wood Age. The numbers were measured
 * offline on four windows of three worlds (docs/superpowers/specs/2026-09-25-home-search-design.md).
 */
public enum HomeKnob implements KnobSpec {

    EXPLORE("home.explore", Kind.BOOL, 1, 0, 1,
            "Whether a party with no HOME goes looking for one. Off, HOME is set by command only — "
                    + "for a test scene that wants its settlers to stay where they were put."),
    SIZE("home.size", Kind.INT, 17, 5, 63,
            "The side of the plot a party claims, in blocks. Rounded up to odd."),
    READ_RADIUS("home.read_radius", Kind.INT, 64, 16, 128,
            "How far from where it stands a settler judges plots, to their centres."),
    BAR_START("home.bar_start", Kind.DOUBLE, 81, 0, 1000,
            "What a place must be worth to settle there at once. 81 was the 75th percentile of the "
                    + "best plot at a first stop, on each world measured."),
    BAR_STEP("home.bar_step", Kind.DOUBLE, 5, 0, 1000,
            "How much less a settler asks for with every leg it has walked since the first plot it "
                    + "found. At 5, about 90% settle within four legs."),
    LEG_LENGTH("home.leg_length", Kind.INT, 64, 16, 128,
            "How far a scout walks between one look round and the next."),
    MAX_LEGS("home.max_legs", Kind.INT, 8, 1, 64,
            "Legs walked after the first plot found before the scout goes back to the best one and "
                    + "settles there, whatever it is worth."),
    MAX_SEARCH("home.max_search", Kind.INT, 32, 1, 256,
            "Legs a scout walks without finding any allowed plot before it gives up for now."),
    GATHER_RADIUS("home.gather_radius", Kind.INT, 16, 2, 64,
            "At a stop the scout waits until every companion is this near before it looks round."),
    GATHER_WAIT("home.gather_wait", Kind.INT, 600, 0, 12_000,
            "The longest the scout waits for its companions at a stop, in ticks: one that fell "
                    + "behind or went to eat does not hold the party forever."),
    HEADING_LAND("home.heading.land", Kind.DOUBLE, 30, 0, 1000,
            "What open land ahead is worth to a heading, at all of it: the share of dry, flat "
                    + "ground 40 to 64 blocks out that way, times this."),
    HEADING_KEEP("home.heading.keep", Kind.DOUBLE, 10, 0, 1000,
            "What keeping the last leg's heading is worth; half of it for one 45° off."),
    HEADING_HOLD("home.heading.hold", Kind.DOUBLE, 40, 0, 1000,
            "What keeping the last heading is worth while no plot has been found yet: a settler "
                    + "crossing a plain picks a way and keeps to it. With momentum alone the lost "
                    + "walked in circles."),

    WATER_WORTH("home.want.water.worth", Kind.DOUBLE, 40, 0, 1000,
            "Water nearby — a river, a lake or the sea, frozen or not."),
    WATER_NEAR("home.want.water.near", Kind.INT, 4, 0, 256,
            "Water within this of the plot's edge is worth all of it."),
    WATER_FAR("home.want.water.far", Kind.INT, 24, 1, 256,
            "Water this far from the plot's edge or further is worth nothing."),
    WATER_MIN("home.want.water.min", Kind.INT, 256, 1, 65_536,
            "The fewest columns a body of water must cover to count: a pond is not a lake."),

    STONE_WORTH("home.want.stone.worth", Kind.DOUBLE, 30, 0, 1000,
            "Stone showing at the surface — a hill or a cliff of it, as the settler has seen it."),
    STONE_NEAR("home.want.stone.near", Kind.INT, 8, 0, 256, "Full worth within this of the plot's edge."),
    STONE_FAR("home.want.stone.far", Kind.INT, 56, 1, 256, "No worth this far or further."),
    STONE_MIN("home.want.stone.min", Kind.INT, 8, 1, 65_536,
            "The fewest exposed blocks that make a stone source."),

    ROOM_WORTH("home.want.room.worth", Kind.DOUBLE, 30, 0, 1000,
            "Spare flat ground beside the plot, for the fields and buildings a settlement adds."),
    ROOM_RADIUS("home.want.room.radius", Kind.INT, 32, 1, 128,
            "How far from the plot's centre the flat ground is counted, off the plot."),
    ROOM_FULL("home.want.room.full", Kind.INT, 3000, 1, 65_536,
            "Flat columns worth all of it. Near the median: at 1,000 nine plots in ten were full "
                    + "and none could be told apart."),

    LAVA_WORTH("home.want.lava.worth", Kind.DOUBLE, 15, 0, 1000,
            "Lava nearby, at the surface, for what it smelts and makes."),
    LAVA_NEAR("home.want.lava.near", Kind.INT, 8, 0, 256, "Full worth within this of the plot's edge."),
    LAVA_FAR("home.want.lava.far", Kind.INT, 48, 1, 256, "No worth this far or further."),
    LAVA_MIN("home.want.lava.min", Kind.INT, 1, 1, 65_536, "The fewest columns of lava that count."),
    LAVA_REFUSE("home.want.lava.refuse", Kind.INT, 4, 0, 64,
            "Lava this close to the plot's edge refuses it. Vanilla lava sets alight a flammable "
                    + "block 3 away."),

    BEE_WORTH("home.want.bee.worth", Kind.DOUBLE, 10, 0, 1000, "A bee nest or hive nearby."),
    BEE_NEAR("home.want.bee.near", Kind.INT, 8, 0, 256, "Full worth within this of the plot's edge."),
    BEE_FAR("home.want.bee.far", Kind.INT, 48, 1, 256, "No worth this far or further."),
    BEE_MIN("home.want.bee.min", Kind.INT, 1, 1, 64, "The fewest nests that count."),

    AVOID_PARTY("home.avoid.party", Kind.INT, 128, 0, 1024,
            "No home within this of another party's plot, edge to edge: close-by settlements are "
                    + "no case a settler allows."),
    AVOID_VILLAGE("home.avoid.village", Kind.INT, 64, 0, 256,
            "No home within this of a village's buildings: expanding later would be a pain."),
    AVOID_MONSTERS("home.avoid.monsters", Kind.INT, 64, 0, 256,
            "No home within this of what keeps spawning monsters: a pillager outpost, a witch hut, "
                    + "a woodland mansion, an ocean monument (#autarkia:home_monsters)."),
    AVOID_TEMPLE("home.avoid.temple", Kind.INT, 8, 0, 256,
            "No home within this of a temple or an igloo (#autarkia:home_temples)."),
    AVOID_PORTAL("home.avoid.portal", Kind.INT, 4, 0, 256,
            "No home within this of a ruined portal, which is used ground.");

    private final String key;
    private final Kind kind;
    private final double def;
    private final double min;
    private final double max;
    private final String doc;

    HomeKnob(String key, Kind kind, double def, double min, double max, String doc) {
        this.key = key;
        this.kind = kind;
        this.def = def;
        this.min = min;
        this.max = max;
        this.doc = doc;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public double def() {
        return def;
    }

    @Override
    public double min() {
        return min;
    }

    @Override
    public double max() {
        return max;
    }

    @Override
    public String doc() {
        return doc;
    }

    /** The value in force. */
    public double d() {
        return AutarkiaConfig.get().d(this);
    }

    public int i() {
        return AutarkiaConfig.get().i(this);
    }

    public boolean b() {
        return AutarkiaConfig.get().b(this);
    }
}
