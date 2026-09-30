package dev.luizloyola.autarkia.core.person;

import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Acts;
import dev.luizloyola.anima.core.brain.task.Hunt;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.inv.ItemSpec;

/**
 * Anima's hunt, behind the act the node table opens. Which animals a Person hunts needs nothing
 * here: it is whatever drops the food wanted and is not in the Person's danger table.
 */
public final class Hunting {

    public static final Act ACT = Acts.register(new Act("autarkia:hunt"));

    private Hunting() {
    }

    public static Method create(ItemSpec wanted) {
        return new Hunt(wanted, ACT);
    }
}
