package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.config.KnobSpec;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;

/**
 * How a party judges a place to live: {@code home.*} in {@code config/autarkia.toml}. One table of
 * wants for the whole search, since HOME is chosen once, in the Wood Age. The numbers were measured
 * offline on four windows of three worlds (docs/superpowers/specs/2026-09-25-home-search-design.md).
 */
public enum HomeKnob implements KnobSpec {

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
    BEE_MIN("home.want.bee.min", Kind.INT, 1, 1, 64, "The fewest nests that count.");

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
}
