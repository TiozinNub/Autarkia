package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.SeekCompany;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Take a found place to the leader: straight to them while they are in sight, else back to where the
 * search set out, the one place a finder knows to look for them. Nothing is known that was not
 * perceived, so a leader who has gone elsewhere is not found, and the finder tries again later.
 */
public final class ReturnAndTell implements CompoundTask {

    private final @Nullable Pos back;
    private final BeingId leader;
    // Appended, never inserted: a saved plan resumes its method by index.
    private final List<Method> methods = List.of(new InSight(), new GoBack());

    public ReturnAndTell(@Nullable Pos back, BeingId leader) {
        this.back = back;
        this.leader = leader;
    }

    public @Nullable Pos back() {
        return back;
    }

    public BeingId leader() {
        return leader;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "take the place to the leader";
    }

    private final class InSight implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return ctx.percepts().being(leader).isPresent();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new SeekCompany(leader));
        }

        @Override
        public String describe() {
            return "go up to them";
        }
    }

    private final class GoBack implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return back != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            Pos at = ctx.percepts().position();
            return Math.hypot(back.x() - at.x(), back.z() - at.z());
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new GoTo(back.x(), back.y(), back.z()), new SeekCompany(leader));
        }

        @Override
        public String describe() {
            return "go back to where we set out";
        }
    }
}
