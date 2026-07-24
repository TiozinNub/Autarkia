package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.act.ScaffoldState;
import dev.luizloyola.autarkia.core.brain.act.Scaffolder;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.log.Category;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Scaffolder} port over a live {@link Person} — one nerd-pole step: jump, place a
 * carried block into the cell just vacated while airborne past block height, land on it. The body
 * owns the jump/place timing (ticked from {@link Person#serverAiStep()}); the brain only asks for
 * steps.
 *
 * <p>Refusals are free, including the ledger at {@link Scaffolder#PILLAR_MAX}; a step that never
 * clears block height within {@link #STEP_TIMEOUT_TICKS} FAILS with nothing consumed.
 */
public final class PersonScaffolder implements Scaffolder {
    /** Ticks a step may take before it is declared dead — a clean jump lands in ~12. */
    private static final int STEP_TIMEOUT_TICKS = 15;

    private final Person person;

    private ScaffoldState state = ScaffoldState.IDLE;
    private @Nullable BlockPos base;
    private @Nullable String itemId;
    private int ticks;
    /**
     * The standing ledger (see {@link Scaffolder#placed()}): cells pushed when their block
     * actually lands in {@link #tick()}, struck by {@link #reclaim}. Body state on purpose —
     * it must outlive any one task so a canceled climb can still be un-built.
     */
    private final Deque<Pos> placed = new ArrayDeque<>();

    public PersonScaffolder(Person person) {
        this.person = person;
    }

    @Override
    public boolean up(String itemId) {
        if (state == ScaffoldState.RISING) {
            return false;
        }
        if (placed.size() >= PILLAR_MAX) {
            return false; // the bodily height cap — un-build before building higher
        }
        if (person.inventory().count(itemId) <= 0) {
            return false;
        }
        Level level = person.level();
        BlockPos feet = person.blockPosition();
        BlockPos headroom = feet.above(2);
        if (!level.getBlockState(headroom).getCollisionShape(level, headroom).isEmpty()
                || !level.getBlockState(feet).canBeReplaced()) {
            return false; 
        }
        this.base = feet;
        this.itemId = itemId;
        this.ticks = 0;
        this.state = ScaffoldState.RISING;
        log("step up", "from " + feet.toShortString());
        return true;
    }

    /** One tick of climb work, from {@link Person#serverAiStep()}; a no-op unless rising. */
    public void tick() {
        if (state != ScaffoldState.RISING) {
            return;
        }
        faceBase(); 
        person.setJumping(true); // held-space semantics: aiStep jumps her when grounded
        if (person.getY() >= base.getY() + 1.0) {
            person.setJumping(false);
            Level level = person.level();
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
            if (!(item instanceof BlockItem blockItem)
                    || !level.getBlockState(base).canBeReplaced()
                    || person.inventory().count(itemId) <= 0) {
                state = ScaffoldState.FAILED;
                log("step failed", "mid-air, cell or item gone at " + base.toShortString());
                return;
            }
            BlockState blockState = blockItem.getBlock().defaultBlockState();
            level.setBlockAndUpdate(base, blockState);
            SoundType sound = blockState.getSoundType();
            level.playSound(null, base, sound.getPlaceSound(), SoundSource.BLOCKS,
                    (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
            person.swing(InteractionHand.MAIN_HAND);
            person.inventory().remove(itemId, 1);
            placed.push(new Pos(base.getX(), base.getY(), base.getZ()));
            state = ScaffoldState.RISEN;
            log("placed", itemId + " at " + base.toShortString());
            return;
        }
        if (++ticks > STEP_TIMEOUT_TICKS) {
            person.setJumping(false);
            state = ScaffoldState.FAILED; 
            log("step failed", "never cleared block height above " + base.toShortString());
        }
    }

    /**
     * Tilt the head and eyes down onto {@link #base}. The placement is straight underfoot, so the
     * yaw toward it is degenerate: keep her travel yaw and only pitch down.
     */
    private void faceBase() {
        Vec3 center = Vec3.atCenterOf(base);
        Vec3 eye = person.getEyePosition();
        double dy = center.y - eye.y;
        double horizontal = Math.hypot(center.x - eye.x, center.z - eye.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        person.setXRot(pitch);
    }

    private void log(String event, String detail) {
        person.journal().record(Category.BODY, "scaffold " + event, detail);
    }

    @Override
    public List<Pos> placed() {
        return List.copyOf(placed);
    }

    @Override
    public void reclaim(Pos cell) {
        placed.remove(cell);
    }

    @Override
    public ScaffoldState state() {
        return state;
    }

    @Override
    public void abort() {
        if (state == ScaffoldState.RISING) {
            person.setJumping(false);
        }
        state = ScaffoldState.IDLE;
    }
}
