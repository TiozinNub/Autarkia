package dev.luizloyola.autarkia.mod.person;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.autarkia.core.person.PickupTally;

/** How a {@link PickupTally} is saved with its Person; apart from it so a test can load it headless. */
public final class PickupCodecs {
    private PickupCodecs() {}

    public static final Codec<PickupTally.State> TALLY = RecordCodecBuilder.create(tally -> tally.group(
            Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("counts").forGetter(PickupTally.State::counts),
            Codec.LONG.fieldOf("since").forGetter(PickupTally.State::since)
    ).apply(tally, PickupTally.State::new));
}
