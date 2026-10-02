package dev.luizloyola.autarkia.mod.builder;

import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.compat.bp.Placer;
import dev.luizloyola.autarkia.core.board.Build;
import dev.luizloyola.autarkia.core.bp.Blueprint;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.Chooser;
import dev.luizloyola.autarkia.core.bp.Diagnostics;
import dev.luizloyola.autarkia.core.bp.Planner;
import dev.luizloyola.autarkia.core.builder.BuildOrder;
import dev.luizloyola.autarkia.core.builder.Laying;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.mod.bp.Blueprints;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/** A structure's build: its plan made again from the record, proved, and laid out in the world. */
public final class Builds {

    /** As a flatten's: neither ahead of every gather nor behind them all (ruling 23). */
    public static final double PRIORITY = 0.5;

    private Builds() {
    }

    /**
     * @param notes what was left out, and why — a step nothing places, a ridge out of reach
     * @return null, with why in {@code notes}, when the plan cannot be made
     */
    public static @Nullable Build of(ServerLevel level, Structure structure, List<String> notes) {
        return of(level, structure, notes, null);
    }

    /**
     * @param only the cells a growth changes: the steps that place nothing else are left out. Null
     *             for the whole building.
     */
    public static @Nullable Build of(ServerLevel level, Structure structure, List<String> notes,
                                     java.util.@Nullable Set<Pos> only) {
        Blueprint bp = Blueprints.find(structure.blueprint()).map(entry -> entry.compiled().blueprint()).orElse(null);
        if (bp == null) {
            notes.add("no blueprint " + structure.blueprint());
            return null;
        }
        Diagnostics out = new Diagnostics();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BuildPlan plan = Planner.plan(bp, Blueprints.dictionary(), Placer.SUPPORT, structure.bindings(),
                structure.variants(), Chooser.random(random), random, out);
        if (plan == null) {
            notes.add("cannot plan " + structure.blueprint() + ": " + out.list());
            return null;
        }
        BuildOrder.Result proved = BuildOrder.prove(plan, Blueprints.dictionary());
        if (!proved.complete()) {
            notes.add(proved.unplaced().size() + " steps no stand reaches");
        }
        BlockPos anchor = new BlockPos(structure.anchor().x(), structure.anchor().y(), structure.anchor().z());
        List<Laying> order = new ArrayList<>();
        int itemless = 0;
        for (BuildOrder.Placed placed : proved.order()) {
            var laying = Placer.laying(anchor, plan, structure.placement(), placed);
            if (laying.isPresent() && only != null && !only.contains(laying.get().cell())
                    && laying.get().also().stream().noneMatch(only::contains)) {
                continue;
            }
            if (laying.isPresent()) {
                order.add(laying.get());
            } else {
                itemless++;
            }
        }
        if (itemless > 0) {
            notes.add(itemless + " steps nothing places");
        }
        Build build = new Build(structure.id(), structure.blueprint(), order, PRIORITY);
        LevelProbe probe = new LevelProbe(level);
        build.standing(step -> step.standsAs(probe.idAt(step.cell().x(), step.cell().y(), step.cell().z()),
                probe.stateAt(step.cell().x(), step.cell().y(), step.cell().z())));
        return build;
    }
}
