package dev.luizloyola.autarkia.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.AngerManagement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A warden's anger at each suspect, for ranking a Person beside the players. Connector-safe. */
@Mixin(AngerManagement.class)
public interface AngerManagementAccessor {

    @Accessor("angerBySuspect")
    Object2IntMap<Entity> autarkia$angerBySuspect();

    @Accessor("highestAnger")
    int autarkia$highestAnger();

    @Accessor("highestAnger")
    void autarkia$setHighestAnger(int anger);
}
