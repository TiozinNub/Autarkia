package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.act.BlockPlacer;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.mod.entity.Person;
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

/**
 * The {@link BlockPlacer} port over a live {@link Person}: one real placement — a carried item
 * (one consumed from the carried inventory, the source of truth the equipment mirror follows), a
 * free target, {@code canSurvive}, arm's reach. Real place sound, arm swing and game event;
 * refusal changes nothing.
 */
public final class PersonBlockPlacer implements BlockPlacer {
    /** Arm's reach in blocks (eye to block center) — same as the breaker's. */
    private static final double REACH = 4.5;

    private final Person person;

    public PersonBlockPlacer(Person person) {
        this.person = person;
    }

    @Override
    public boolean place(String itemId, Pos cell) {
        if (person.inventory().count(itemId) <= 0) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        if (!(item instanceof BlockItem blockItem)) {
            return false;
        }
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        Level level = person.level();
        BlockState state = blockItem.getBlock().defaultBlockState();
        if (!level.getBlockState(pos).canBeReplaced() || !state.canSurvive(level, pos)) {
            return false;
        }
        if (person.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            return false;
        }
        person.faceBlock(pos); 
        level.setBlockAndUpdate(pos, state);
        // The world hears it: the vibration bus (sculk, other Persons' ears) and the
        // place-marks that let a WATCHING peer read the swing as building, not mining.
        level.gameEvent(net.minecraft.world.level.gameevent.GameEvent.BLOCK_PLACE, pos,
                net.minecraft.world.level.gameevent.GameEvent.Context.of(person, state));
        SoundType sound = state.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        person.swing(InteractionHand.MAIN_HAND);
        person.inventory().remove(itemId, 1);
        return true;
    }
}
