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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Scaffolder} port over a live {@link Person} — one nerd-pole step: jump, place a carried
 * block into the just-vacated cell while airborne past block height, land on it. Ticked from
 * {@link Person#serverAiStep()}; one item leaves the carried inventory and the mirror follows.
 *
 * <p>Refusals are free: no such item, no headroom two above the feet, a step in flight, the ledger
 * at {@link Scaffolder#PILLAR_MAX}, or a cell that has beaten them {@link #ATTEMPTS_PER_CELL} times.
 * A step that never clears block height within {@link #STEP_TIMEOUT_TICKS} FAILS, nothing consumed.
 *
 * <p><b>The body steps to the middle of its cell before jumping.</b> The headroom check reads one
 * column ({@code feet.above(2)}) but the box is 0.6 wide, so off-centre it bonks a block that check
 * never looked at; centring makes the cheap check sound (decision: Luiz). The live case: a leaf one
 * cell over, the box 0.182 into that column, 95 dead jumps in a row.
 */
public final class PersonScaffolder implements Scaffolder {
    /** Ticks a step may take before it is declared dead — a clean jump lands in ~12. */
    private static final int STEP_TIMEOUT_TICKS = 15;

    /** Ticks the centring shuffle may take before the step dies — a worst-case corner needs ~5. */
    private static final int CENTRE_TIMEOUT_TICKS = 12;

    /** Centred, per axis: the 0.6-wide box fits wholly inside up to 0.2, leaving 0.05 of margin. */
    private static final double CENTRE_TOLERANCE = 0.15;

    /** Half-throttle scuff: at ~0.1 of a block per tick the body cannot cross from outside the
     *  tolerance on one side to outside it on the other, so it settles in the middle. */
    private static final float CENTRE_THROTTLE = 0.5F;

    /**
     * Consecutive dead steps from the same cell before {@link #up} refuses, routing the caller
     * into its give-up branch. Recentring fixes the common cause; a cobweb overhead, a shoving
     * neighbour or honey underfoot defeat it, and unbounded the caller re-asks forever (the
     * 16-tick jump loop this class was fixed for).
     */
    private static final int ATTEMPTS_PER_CELL = 3;

    private final Person person;

    private ScaffoldState state = ScaffoldState.IDLE;
    private @Nullable BlockPos base;
    private @Nullable String itemId;
    private int ticks;
    /** Ticks spent shuffling toward the middle of {@link #base}; separate from {@link #ticks} so
     * the shuffle cannot eat the jump's budget. */
    private int centringTicks;
    /** True while this step is still walking to the middle of {@link #base} — see the class doc. */
    private boolean centring;
    /**
     * Body state beside the ledger and for the same reason: a cell that keeps beating them is
     * about this body's situation, not the task that asked, so the streak survives task churn and
     * a mid-climb suspension. Cleared by succeeding, or by asking from anywhere else.
     */
    private @Nullable BlockPos failedCell;
    private int failedStreak;
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
        if (!feet.equals(failedCell)) {
            failedCell = null; // asked from somewhere else — a different spot is a different problem
            failedStreak = 0;
        } else if (failedStreak >= ATTEMPTS_PER_CELL) {
            return false; // stop feeding the loop
        }
        BlockPos headroom = feet.above(2);
        if (!level.getBlockState(headroom).getCollisionShape(level, headroom).isEmpty()
                || !level.getBlockState(feet).canBeReplaced()) {
            return false; 
        }
        this.base = feet;
        this.itemId = itemId;
        this.ticks = 0;
        this.centringTicks = 0;
        this.centring = !centred(feet);
        this.state = ScaffoldState.RISING;
        log("step up", "from " + feet.toShortString() + (this.centring ? " (centring first)" : ""));
        return true;
    }

    /**
     * True when the box sits wholly inside {@code cell} — the stance the single-column headroom
     * check in {@link #up} assumes. Per axis, because the box is axis-aligned.
     */
    private boolean centred(BlockPos cell) {
        return Math.abs(person.getX() - (cell.getX() + 0.5)) <= CENTRE_TOLERANCE
                && Math.abs(person.getZ() - (cell.getZ() + 0.5)) <= CENTRE_TOLERANCE;
    }

    /** One tick of climb work, from {@link Person#serverAiStep()}; a no-op unless rising. */
    public void tick() {
        if (state != ScaffoldState.RISING) {
            return;
        }
        if (centring && !stepToMiddle()) {
            return; // still shuffling (or the step just died trying) — no jump input this tick
        }
        person.faceBlock(base); 
        person.setJumping(true); // held-space semantics: aiStep jumps them when grounded
        if (person.getY() >= base.getY() + 1.0) {
            person.setJumping(false);
            Level level = person.level();
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
            if (!(item instanceof BlockItem blockItem)
                    || !level.getBlockState(base).canBeReplaced()
                    || person.inventory().count(itemId) <= 0) {
                fail("mid-air, cell or item gone at " + base.toShortString());
                return;
            }
            BlockState blockState = blockItem.getBlock().defaultBlockState();
            level.setBlockAndUpdate(base, blockState);
            level.gameEvent(net.minecraft.world.level.gameevent.GameEvent.BLOCK_PLACE, base,
                    net.minecraft.world.level.gameevent.GameEvent.Context.of(person, blockState));
            SoundType sound = blockState.getSoundType();
            level.playSound(null, base, sound.getPlaceSound(), SoundSource.BLOCKS,
                    (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
            person.swing(InteractionHand.MAIN_HAND);
            person.inventory().remove(itemId, 1);
            placed.push(new Pos(base.getX(), base.getY(), base.getZ()));
            state = ScaffoldState.RISEN;
            failedCell = null; // the cell yielded — whatever was wrong with it is over
            failedStreak = 0;
            log("placed", itemId + " at " + base.toShortString());
            return;
        }
        if (++ticks > STEP_TIMEOUT_TICKS) {
            fail("never cleared block height above " + base.toShortString());
        }
    }

    /**
     * One tick of the centring shuffle toward the middle of the feet cell (see the class doc).
     * Returns true the moment the body is centred, so the jump goes on that same tick.
     */
    private boolean stepToMiddle() {
        if (centred(base)) {
            centring = false;
            return true;
        }
        if (++centringTicks > CENTRE_TIMEOUT_TICKS) {
            fail("never reached the middle of " + base.toShortString());
            return false;
        }
        double dx = base.getX() + 0.5 - person.getX();
        double dz = base.getZ() + 0.5 - person.getZ();
        // Minecraft yaw: 0 faces +Z and increases clockwise, so facing a point is atan2(dz, dx)
        // offset by -90 — the Navigator's convention. driveForward also clears the jump input.
        person.driveForward((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F, CENTRE_THROTTLE);
        return false;
    }

    /**
     * Kill the step in flight, remembering the cell so {@link #up} can stop retrying it after
     * {@link #ATTEMPTS_PER_CELL}.
     */
    private void fail(String detail) {
        person.setJumping(false);
        centring = false;
        state = ScaffoldState.FAILED;
        if (base.equals(failedCell)) {
            failedStreak++;
        } else {
            failedCell = base;
            failedStreak = 1;
        }
        log("step failed", detail);
        if (failedStreak >= ATTEMPTS_PER_CELL) {
            // up() just returns false, and a silent give-up is what makes the next stuck Person
            // invisible in the journal.
            log("step gave up", failedStreak + " dead steps from " + base.toShortString()
                    + " — refusing further climbs from this cell");
        }
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
        centring = false;
        state = ScaffoldState.IDLE;
    }
}
