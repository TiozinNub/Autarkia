package dev.luizloyola.autarkia.mixin;

import dev.luizloyola.autarkia.mod.entity.Person;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.AngerLevel;
import net.minecraft.world.entity.monster.warden.AngerManagement;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Among suspects equally angering, a warden ranks a Person with the players, ahead of mobs. Its
 * ranking is recomputed whenever a Person is one of the two, the same steps vanilla takes.
 */
@Mixin(targets = "net.minecraft.world.entity.monster.warden.AngerManagement$Sorter")
public abstract class WardenAngerSorterMixin {

    @Shadow @Final private AngerManagement angerManagement;

    @Inject(method = "compare(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)I",
            at = @At("HEAD"), cancellable = true)
    private void autarkia$aPersonAsAPlayer(Entity first, Entity second, CallbackInfoReturnable<Integer> cir) {
        if (!(first instanceof Person || second instanceof Person) || first.equals(second)) {
            return;
        }
        AngerManagementAccessor anger = (AngerManagementAccessor) angerManagement;
        Object2IntMap<Entity> bySuspect = anger.autarkia$angerBySuspect();
        int firstAnger = bySuspect.getOrDefault(first, 0);
        int secondAnger = bySuspect.getOrDefault(second, 0);
        anger.autarkia$setHighestAnger(Math.max(anger.autarkia$highestAnger(), Math.max(firstAnger, secondAnger)));
        boolean firstAngry = AngerLevel.byAnger(firstAnger).isAngry();
        boolean secondAngry = AngerLevel.byAnger(secondAnger).isAngry();
        if (firstAngry != secondAngry) {
            cir.setReturnValue(firstAngry ? -1 : 1);
            return;
        }
        boolean firstPlayer = first instanceof Player || first instanceof Person;
        boolean secondPlayer = second instanceof Player || second instanceof Person;
        cir.setReturnValue(firstPlayer != secondPlayer ? (firstPlayer ? -1 : 1)
                : Integer.compare(secondAnger, firstAnger));
    }
}
