package dev.luizloyola.autarkia.mixin;

import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.player.Player;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A creaking treats a Person as a player: it freezes while one looks at it, wakes when one looks at
 * it from under 12 blocks, hunts it, and goes back to sleep when nobody it could attack is near.
 *
 * <p>Vanilla's {@code checkCanMove} walks a {@code List<Player>}, so with a Person near this runs the
 * same loop over players and Persons instead; with none near, vanilla's runs untouched. A Person
 * cannot be passed to {@code activate(Player)}, so its body is repeated here for one.
 */
@Mixin(Creaking.class)
public abstract class CreakingMixin {

    private static final double WAKE_DISTANCE_SQR = 144.0;

    @Inject(method = "checkCanMove", at = @At("HEAD"), cancellable = true)
    private void autarkia$withPersons(CallbackInfoReturnable<Boolean> cir) {
        Creaking self = (Creaking) (Object) this;
        double range = self.getAttributeValue(Attributes.FOLLOW_RANGE);
        List<LivingEntity> near = new ArrayList<>();
        for (LivingEntity entity : self.getBrain().getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES).orElse(List.of())) {
            if (entity instanceof Person && self.closerThan(entity, range)) {
                near.add(entity);
            }
        }
        if (near.isEmpty()) {
            return;
        }
        near.addAll(self.getBrain().getMemory(MemoryModuleType.NEAREST_PLAYERS).orElse(List.of()));
        near.sort(Comparator.comparingDouble(self::distanceToSqr));
        boolean active = self.isActive();
        boolean anyTarget = false;
        LivingEntity firstPerson = null;
        for (LivingEntity entity : near) {
            if (!self.canAttack(entity) || self.isAlliedTo(entity)) {
                continue;
            }
            anyTarget = true;
            if (firstPerson == null && entity instanceof Person) {
                firstPerson = entity;
            }
            if ((!active || LivingEntity.PLAYER_NOT_WEARING_DISGUISE_ITEM.test(entity))
                    && self.isLookingAtMe(entity, 0.5, false, true, self.getEyeY(),
                            self.getY() + 0.5 * self.getScale(), (self.getEyeY() + self.getY()) / 2.0)) {
                if (active) {
                    cir.setReturnValue(false);
                    return;
                }
                if (entity.distanceToSqr(self) < WAKE_DISTANCE_SQR) {
                    if (entity instanceof Player player) {
                        self.activate(player);
                    } else {
                        wake(self, entity);
                    }
                    cir.setReturnValue(false);
                    return;
                }
            }
        }
        if (!anyTarget && active) {
            self.deactivate();
        } else if (active && firstPerson != null
                && self.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).isEmpty()) {
            // Vanilla's idle re-acquire reads the nearest attackable player only.
            self.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, firstPerson);
        }
        cir.setReturnValue(true);
    }

    /** {@code Creaking.activate(Player)}, for a Person. */
    private static void wake(Creaking self, LivingEntity target) {
        self.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
        self.gameEvent(GameEvent.ENTITY_ACTION);
        self.makeSound(SoundEvents.CREAKING_ACTIVATE);
        self.setIsActive(true);
    }
}
