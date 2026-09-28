package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.BuildPlan.CellKind;
import dev.luizloyola.autarkia.core.bp.Dictionary;
import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A plan split into the steps that build it, each in its {@link Section} (builder spec,
 * *Sections*). Read from the plan alone, in the drawing's coordinates, so a turned or mirrored
 * placement builds in the same order; clearing, earthwork and cleanup read the world, and come
 * with it.
 *
 * <p>Structural is anything with collision that is not a door, a fixture, a furnishing or hung on
 * something. Of that, the <b>floor</b> is at layer 0 and below, or the lowest block of a column
 * with a roofed cell over it; the <b>ceiling</b> covers a roofed cell or has open sky over it — eaves, the
 * roof's slope, its ridge; the rest is <b>walls</b>, gables and the frame under the roof included.
 * A light hung on a wall or standing on a floor is one of the <b>lights</b>; a ladder goes with the
 * <b>walls</b>. Everything else is
 * decor, <b>interior</b> when roofed. <i>Roofed</i> is open with collision somewhere above — the
 * spec's reading, which an open window does not fool.
 */
public final class Sections {

    static final String DOORS = "minecraft:doors";
    static final String TRAPDOORS = "minecraft:trapdoors";
    static final String GATES = "minecraft:fence_gates";
    static final String CLIMBABLE = "minecraft:climbable";
    static final String WALL_HANGING = "minecraft:wall_hanging_signs";
    static final String CEILING_HANGING = "minecraft:ceiling_hanging_signs";
    static final String FURNISHING = "autarkia:furnishing";

    private final BuildPlan plan;
    private final Dictionary dict;
    private final boolean[] open;
    /** Collision somewhere above, in the same column. */
    private final boolean[] covered;
    private final boolean[] roofed;
    /** Per column, the lowest roofed layer, or {@code Integer.MAX_VALUE}. */
    private final int[] lowestRoofed;
    /** Per column, the lowest layer that places a block, or {@code Integer.MAX_VALUE}. */
    private final int[] lowestPlaced;

    private Sections(BuildPlan plan, Dictionary dict) {
        this.plan = plan;
        this.dict = dict;
        int cells = (plan.maxLayer() - plan.minLayer() + 1) * plan.depth() * plan.width();
        open = new boolean[cells];
        this.covered = new boolean[cells];
        roofed = new boolean[cells];
        lowestRoofed = new int[plan.depth() * plan.width()];
        Arrays.fill(lowestRoofed, Integer.MAX_VALUE);
        lowestPlaced = new int[plan.depth() * plan.width()];
        Arrays.fill(lowestPlaced, Integer.MAX_VALUE);
        for (int z = 0; z < plan.depth(); z++) {
            for (int x = 0; x < plan.width(); x++) {
                boolean covered = false;
                for (int layer = plan.maxLayer(); layer >= plan.minLayer(); layer--) {
                    int i = index(layer, x, z);
                    open[i] = isOpen(layer, x, z);
                    this.covered[i] = covered;
                    roofed[i] = open[i] && covered;
                    if (roofed[i]) {
                        lowestRoofed[z * plan.width() + x] = layer;
                    }
                    if (plan.kind(layer, x, z) == CellKind.BLOCK) {
                        lowestPlaced[z * plan.width() + x] = layer;
                    }
                    covered |= !open[i] && plan.kind(layer, x, z) == CellKind.BLOCK;
                }
            }
        }
    }

    /** Every placement the plan asks for, in the drawing's layer, row and column order. */
    public static List<Step> of(BuildPlan plan, Dictionary dict) {
        return new Sections(plan, dict).steps();
    }

    private List<Step> steps() {
        List<Step> steps = new ArrayList<>();
        Set<Cell> taken = new HashSet<>();
        for (int layer = plan.minLayer(); layer <= plan.maxLayer(); layer++) {
            for (int z = 0; z < plan.depth(); z++) {
                for (int x = 0; x < plan.width(); x++) {
                    Cell cell = new Cell(layer, x, z);
                    Outcome state = plan.state(layer, x, z);
                    if (state == null || taken.contains(cell)) {
                        continue;
                    }
                    BlockInfo info = dict.block(state.block()).orElse(null);
                    List<Cell> cells = new ArrayList<>(List.of(cell));
                    List<Outcome> states = new ArrayList<>(List.of(state));
                    Cell other = info == null ? null : partner(cell, state, info);
                    if (other != null) {
                        // A bed is placed at its foot, so its foot leads.
                        int at = "head".equals(prop(state, info, "part")) ? 0 : 1;
                        cells.add(at, other);
                        states.add(at, plan.state(other.layer(), other.x(), other.z()));
                        taken.add(other);
                    }
                    Cell holder = info == null ? null : holder(cell, state, info);
                    steps.add(new Step(section(cell, info, other != null, holder), cells, states, holder));
                }
            }
        }
        return steps;
    }

    private Section section(Cell cell, @Nullable BlockInfo info, boolean fixture, @Nullable Cell holder) {
        Section decor = covered[index(cell.layer(), cell.x(), cell.z())] ? Section.INTERIOR : Section.EXTERIOR;
        if (info == null) {
            return decor;
        }
        if (info.is(DOORS) || info.is(TRAPDOORS) || info.is(GATES)) {
            return Section.DOORS;
        }
        if (holder != null && info.light() > 0 && holder.layer() <= cell.layer()) {
            return Section.LIGHTS;
        }
        // A ladder goes up with the wall it hangs on: it is how the builders reach the storey above.
        if (info.is(CLIMBABLE) && holder != null) {
            return Section.WALLS;
        }
        if (!info.obstructs() || fixture || holder != null || info.is(FURNISHING)) {
            return decor;
        }
        int column = cell.z() * plan.width() + cell.x();
        if (cell.layer() <= 0 || cell.layer() == lowestPlaced[column] && cell.layer() < lowestRoofed[column]) {
            return Section.FLOOR;
        }
        boolean coversRoofed = roofed(cell.layer() - 1, cell.x(), cell.z());
        boolean openSky = cell.layer() == plan.maxLayer()
                || open[index(cell.layer() + 1, cell.x(), cell.z())] && !roofed(cell.layer() + 1, cell.x(), cell.z());
        return coversRoofed || openSky ? Section.CEILING : Section.WALLS;
    }

    /** A fixture's other half, when this is the half its item is placed as. */
    private @Nullable Cell partner(Cell cell, Outcome state, BlockInfo info) {
        if (info.has("half", "lower") && info.has("half", "upper")) {
            return "lower".equals(prop(state, info, "half")) ? matching(cell.offset(1, 0, 0), state) : null;
        }
        if (info.has("part", "head") && info.has("part", "foot") && "head".equals(prop(state, info, "part"))) {
            // A bed's facing points from foot to head.
            Facing facing = Facing.of(String.valueOf(prop(state, info, "facing")));
            return facing == null ? null : matching(cell.offset(0, -facing.dx, -facing.dz), state);
        }
        return null;
    }

    private @Nullable Cell matching(Cell cell, Outcome state) {
        if (!plan.contains(cell.layer(), cell.x(), cell.z())) {
            return null;
        }
        Outcome there = plan.state(cell.layer(), cell.x(), cell.z());
        return there != null && there.block().equals(state.block()) ? cell : null;
    }

    /**
     * What a block needs beside it to stay, as vanilla drops it without: the wall behind a wall
     * torch, a ladder or a wall button, the ceiling over a hanging lantern, the floor under a torch,
     * a door or a carpet. A block with collision stands anywhere.
     */
    private @Nullable Cell holder(Cell cell, Outcome state, BlockInfo info) {
        String face = prop(state, info, "face");
        Facing facing = Facing.of(String.valueOf(prop(state, info, "facing")));
        if (face != null) {
            return switch (face) {
                case "floor" -> cell.offset(-1, 0, 0);
                case "ceiling" -> cell.offset(1, 0, 0);
                default -> facing == null ? null : cell.offset(0, -facing.dx, -facing.dz);
            };
        }
        String hanging = prop(state, info, "hanging");
        if (hanging != null) {
            return cell.offset(hanging.equals("true") ? 1 : -1, 0, 0);
        }
        boolean onWall = dict.wallTwins().containsValue(info.id()) || info.is(WALL_HANGING) || info.is(CLIMBABLE);
        if (onWall) {
            return facing == null ? null : cell.offset(0, -facing.dx, -facing.dz);
        }
        if (info.is(CEILING_HANGING)) {
            return cell.offset(1, 0, 0);
        }
        boolean stands = info.is(DOORS) || !info.obstructs() && !info.is(TRAPDOORS);
        return stands ? cell.offset(-1, 0, 0) : null;
    }

    private static @Nullable String prop(Outcome state, BlockInfo info, String key) {
        String value = state.props().get(key);
        return value != null ? value : info.defaults().get(key);
    }

    private boolean isOpen(int layer, int x, int z) {
        return switch (plan.kind(layer, x, z)) {
            case AIR -> true;
            case KEEP -> layer >= 1;
            case TERRAIN -> false;
            case BLOCK -> {
                Outcome state = plan.state(layer, x, z);
                BlockInfo info = state == null ? null : dict.block(state.block()).orElse(null);
                yield info != null && !info.obstructs();
            }
        };
    }

    private boolean roofed(int layer, int x, int z) {
        return plan.contains(layer, x, z) && roofed[index(layer, x, z)];
    }

    private int index(int layer, int x, int z) {
        return ((layer - plan.minLayer()) * plan.depth() + z) * plan.width() + x;
    }
}
