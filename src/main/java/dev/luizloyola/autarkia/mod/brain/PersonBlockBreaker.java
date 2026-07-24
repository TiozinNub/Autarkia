package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.act.BlockBreaker;
import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.compat.sense.LevelProbe;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The {@link BlockBreaker} port over a live {@link Person} — vanilla-fidelity block breaking
 * without a {@code Player}: the survival progress formula (block hardness, the HELD stack's
 * destroy speed, the correct-tool divisor — a bare hand takes ~3s on a log), the shared crack
 * animation, real drops, and 0.005 exhaustion per broken block onto {@link Person#needs()}.
 *
 * <p>Owned and ticked by the body ({@link Person#serverAiStep()}), exposed to the brain as a port:
 * the machine lives with the body, the brain only holds intent. Every mid-break tick re-validates
 * — a swapped block or a body out of reach fails the break, and the held item is re-read, so a
 * mid-break tool swap changes speed.
 */
public final class PersonBlockBreaker implements BlockBreaker {
    /** Arm's reach in blocks (eye to block center) — the survival player's block-interaction range. */
    private static final double REACH = 4.5;
    /** Vanilla's per-block exhaustion for breaking (verified against the player mining path). */
    private static final float EXHAUSTION_PER_BLOCK = 0.005F;

    private final Person person;

    private BreakState state = BreakState.IDLE;
    private @Nullable BlockPos target;
    /** The block we started on — a different block appearing at {@link #target} fails the break. */
    private @Nullable BlockState begunOn;
    /** Accumulated progress 0..1 (vanilla's destroy-progress scale). */
    private float progress;
    /** Last crack stage broadcast (0–9), or -1 when none is showing. */
    private int sentStage = -1;

    public PersonBlockBreaker(Person person) {
        this.person = person;
    }

    @Override
    public boolean begin(Pos cell) {
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        Level level = person.level();
        BlockState blockState = level.getBlockState(pos);
        if (blockState.isAir() || blockState.getDestroySpeed(level, pos) < 0 || !inReach(pos)
                || !LevelProbe.armPathClear(level, person.getEyePosition(), pos)) {
            return false; // includes a blocked arm path: no breaking logs through the canopy
        }
        clearCrack();
        this.target = pos;
        this.begunOn = blockState;
        this.progress = 0.0F;
        this.state = BreakState.BREAKING;
        return true;
    }

    /** One tick of arm work, from {@link Person#serverAiStep()}; a no-op unless mid-break. */
    public void tick() {
        if (state != BreakState.BREAKING) {
            return;
        }
        Level level = person.level();
        BlockState now = level.getBlockState(target);
        if (now.getBlock() != begunOn.getBlock() || !inReach(target)
                || !LevelProbe.armPathClear(level, person.getEyePosition(), target)) {
            fail(); // moved, block swapped, or something grew between arm and block
            return;
        }
        float hardness = now.getDestroySpeed(level, target);
        if (hardness < 0) {
            fail();
            return;
        }
        person.faceBlock(target); 
        progress += perTick(now, hardness);
        // Every tick, like a mining player (continueDestroyBlock does this): swing()'s own
        // guard restarts the animation at half duration — the player arm's mining cadence, owned by
        // vanilla — and only broadcasts on an actual restart, so this does not spam packets.
        person.swing(InteractionHand.MAIN_HAND);
        if (progress >= 1.0F) {
            clearCrack();
            // The harvest check vanilla's player path applies before dropping: stone punched
            // bare-handed breaks, slowly, but yields nothing.
            ItemStack held = person.getMainHandItem();
            boolean drops = !now.requiresCorrectToolForDrops() || held.isCorrectToolForDrops(now);
            // Vanilla tool wear (Item.mineBlock): one durability per broken block of any
            // hardness. Damaging the VANILLA held stack is deliberate — the two-way equipment
            // mirror pulls the change back into the carried inventory, the source of truth.
            if (!held.isEmpty() && hardness > 0.0F) {
                held.hurtAndBreak(1, person, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            }
            level.destroyBlock(target, drops, person);
            person.needs().exhaust(EXHAUSTION_PER_BLOCK);
            state = BreakState.FINISHED;
            return;
        }
        int stage = Math.min(9, (int) (progress * 10.0F));
        if (stage != sentStage) {
            level.destroyBlockProgress(person.getId(), target, stage);
            sentStage = stage;
        }
    }

    @Override
    public BreakState state() {
        return state;
    }

    @Override
    public void abort() {
        clearCrack();
        state = BreakState.IDLE;
    }

    /** The survival player's destroy-progress formula: held speed / hardness / divisor. */
    private float perTick(BlockState blockState, float hardness) {
        ItemStack held = person.getMainHandItem();
        float speed = held.getDestroySpeed(blockState);
        boolean harvest = !blockState.requiresCorrectToolForDrops()
                || held.isCorrectToolForDrops(blockState);
        return speed / hardness / (harvest ? 30.0F : 100.0F);
    }

    private boolean inReach(BlockPos pos) {
        return person.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= REACH * REACH;
    }

    private void fail() {
        clearCrack();
        state = BreakState.FAILED;
    }

    private void clearCrack() {
        if (sentStage >= 0) {
            person.level().destroyBlockProgress(person.getId(), target, -1);
            sentStage = -1;
        }
    }
}
