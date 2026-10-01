package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.AchieveTask;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Set;

/**
 * Hold a sound tool of this family at the age's tier — {@link ToolUp}'s errand. The tier is read
 * as the errand runs, so an age reached mid-errand raises what it makes.
 */
public final class KeepTool implements AchieveTask {

    private final Tools.Family family;
    private final List<Method> methods = List.of(new Make());

    public KeepTool(Tools.Family family) {
        this.family = family;
    }

    public Tools.Family family() {
        return family;
    }

    @Override
    public boolean satisfied(BrainContext ctx) {
        return Tools.covered(family, Tools.currentTier(family, ctx.gate()),
                ctx.percepts().inventory(), ctx.profile().d(ProfileAspect.HANDLING_SPARE_BELOW));
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "keep " + family.one();
    }

    private final class Make implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        /**
         * One more than is held of the exact item: a worn stone axe would otherwise count as the
         * stone axe being made, and the family would let the old wooden one count too.
         */
        @Override
        public List<Task> decompose(BrainContext ctx) {
            String id = family.at(Tools.TIERS.get(Tools.currentTier(family, ctx.gate())));
            int held = ctx.percepts().inventory().count(id);
            return List.of(new ObtainItem(ItemSpec.anyOf(Set.of(id)), held + 1));
        }

        @Override
        public String describe() {
            return "make one";
        }
    }
}
