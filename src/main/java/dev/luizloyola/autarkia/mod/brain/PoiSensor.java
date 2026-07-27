package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.compat.sense.LevelProbe;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiSensorCore;
import dev.luizloyola.autarkia.core.brain.knowledge.SenseEvent;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.log.Category;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Mounts the pure {@link PoiSensorCore} pipeline on a {@link Person}: only a mounting bracket and
 * the Minecraft boundary. A <em>body</em> sense, not a brain organ — it runs beside the brain in
 * {@code serverAiStep} and writes into the person's knowledge; the brain reads memory, never the
 * world.
 *
 * <p>Resolved lazily on first tick: the {@code PersonId} and the running server are absent at
 * construction but guaranteed by the top of {@code Person.tick()}. What it learns is narrated to the
 * journal ({@link Category#SENSE}), the same events the POI viewer's discovery chat hooks.
 */
public final class PoiSensor {
    private final Person person;
    private @Nullable KnowledgeData data;
    private @Nullable PoiSensorCore core;
    private @Nullable LevelProbe probe;

    public PoiSensor(Person person) {
        this.person = person;
    }

    /** One perception tick, from {@link Person#serverAiStep()}. */
    public void tick() {
        ServerLevel level = (ServerLevel) this.person.level();
        if (this.core == null) {
            this.data = KnowledgeData.get(level.getServer());
            this.core = new PoiSensorCore(
                    this.data.registry().forPerson(this.person.getPersonId()));
            this.probe = new LevelProbe(this.person);
        }
        BlockPos feet = this.person.blockPosition();
        List<SenseEvent> events = this.core.tick(
                new Pos(feet.getX(), feet.getY(), feet.getZ()), level.getGameTime(), this.probe);
        // Unconditional: setDirty is a boolean flag, and knowledge mutates silently (refreshes
        // don't surface as events) — cheaper to always flag than to track what changed.
        this.data.setDirty();
        for (SenseEvent event : events) {
            this.person.journal().record(Category.SENSE, verb(event.type()), describe(event));
            KnowledgeViewer.onEvent(level.getServer(), this.person.getPersonId(),
                    this.person.getName().getString(), event);
        }
    }

    /** The journal's event column, one word per outcome. */
    private static String verb(SenseEvent.Type type) {
        return switch (type) {
            case NOTED -> "noticed";
            case FORGOT -> "forgot";
            case OVERLOOKED -> "overlooked";
            case DISMISSED -> "dismissed";
        };
    }

    /** Transient claim count — the debug command's "how full is the dismissal index" line. */
    public int claimCount() {
        return this.core == null ? 0 : this.core.claimCount();
    }

    /** One line description, shared with the viewer chat:
     *  {@code TREE (10, 64, 8) 4 logs} / {@code WATER … partial}. */
    static String describe(SenseEvent event) {
        StringBuilder line = new StringBuilder(event.kind().name())
                .append(" (").append(event.anchor().x()).append(", ").append(event.anchor().y())
                .append(", ").append(event.anchor().z()).append(")");
        PoiMemory memory = event.memory();
        if (memory != null) {
            line.append(' ').append(memory.units())
                    .append(memory.kind() == PoiKind.TREE ? " logs" : " cells");
            if (memory.partial()) {
                line.append(", partial");
            }
        }
        return line.toString();
    }
}
