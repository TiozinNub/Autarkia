package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.history.Doing;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.TreeSet;

/**
 * A settler's work as they tell it afterwards — Autarkia's doings, declared by the board's work
 * items the way Anima's instincts declare theirs (2026-09-23-small-talk-history-design.md).
 *
 * <p>Registered the moment this class loads; {@code AutarkiaMod} touches it at bootstrap, or a
 * saved history naming one of these would load before the word existed and be dropped.
 */
public final class WorkDoings {

    /** Slots: the goods, and what they were for. */
    public static final Doing GATHERING = Doings.register(new Doing(
            "gathering", "autarkia.doing.gathering", List.of("item", "for"), true));
    /** Slot: the goods a standing want keeps. */
    public static final Doing STOCKING_UP = Doings.register(new Doing(
            "stocking_up", "autarkia.doing.stocking_up", List.of("item"), true));
    /** Slot: what was cleared — the clearing's kind. */
    public static final Doing CLEARING = Doings.register(new Doing(
            "clearing", "autarkia.doing.clearing", List.of("what"), true));
    /** Slot: what the ground was being walked for. */
    public static final Doing SURVEYING = Doings.register(new Doing(
            "surveying", "autarkia.doing.surveying", List.of("what"), true));
    public static final Doing STOWING = Doings.register(new Doing(
            "stowing", "autarkia.doing.stowing", List.of(), true));
    /** Not remembered: picking up after oneself is nothing to talk about. */
    public static final Doing GLEANING = Doings.register(new Doing(
            "gleaning", "autarkia.doing.gleaning", List.of(), false));
    /** Not remembered either: sorting one's own pack is nothing to talk about. */
    public static final Doing TIDYING = Doings.register(new Doing(
            "tidying", "autarkia.doing.tidying", List.of(), false));
    /** Slot: the station put down — a workbench, a chest. */
    public static final Doing SETTING_UP = Doings.register(new Doing(
            "setting_up", "autarkia.doing.setting_up", List.of("what"), true));
    /** Slot: what was taken out — coming back to a furnace. */
    public static final Doing TENDING = Doings.register(new Doing(
            "tending", "autarkia.doing.tending", List.of("what"), true));
    /** Slot: what was put in to smelt. */
    public static final Doing FIRING = Doings.register(new Doing(
            "firing", "autarkia.doing.firing", List.of("what"), true));
    /** Pulling up the ground's plants, so it is ready to build on. */
    public static final Doing CLEARING_PLANTS = Doings.register(new Doing(
            "clearing_plants", "autarkia.doing.clearing_plants", List.of(), true));
    /** Looking for somewhere to live. */
    /** Cutting and filling ground level. */
    public static final Doing LEVELLING = Doings.register(new Doing(
            "levelling", "autarkia.doing.levelling", List.of(), true));
    public static final Doing EXPLORING = Doings.register(new Doing(
            "exploring", "autarkia.doing.exploring", List.of(), true));

    /**
     * What every gather delivers to today. A building project that starts gathers will pass what it
     * is building instead, which is why the slot exists before anything fills it differently.
     */
    public static final Slot FOR_THE_STORES = Slot.lang("autarkia.purpose.stores");

    private WorkDoings() {
    }

    /**
     * A spec as words: one of Autarkia's families by its own lang key, a literal the gather command
     * built by its first item, which the game names.
     */
    public static Slot goods(ItemSpec spec) {
        return ItemSpec.literalIds(spec)
                .map(ids -> Slot.item(new TreeSet<>(ids).first()))
                .orElseGet(() -> Slot.lang("autarkia.goods." + spec.name()));
    }

    public static Slot cleared(Felling clearing) {
        return Slot.lang("autarkia.clearing." + clearing.id());
    }
}
