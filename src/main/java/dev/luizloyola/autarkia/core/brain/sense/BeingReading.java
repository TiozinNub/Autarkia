package dev.luizloyola.autarkia.core.brain.sense;

import org.jspecify.annotations.Nullable;

/**
 * One raw observation of one living body, as the mod's eyes deliver it to the
 * {@link BeingSensorCore}: what it TRULY is, the observable facts, the sight-tier extras (gear,
 * profession, a custom name), and the classified {@link Being.Activity} — idle for anything not a
 * person. A reading holds nothing back; the channel it came through, the masking (the
 * identification ladder's) and how far to trust it over time are the SENSOR's business.
 *
 * @param name the custom/display name a SIGHTING could read — empty for an unnamed creature
 * @param herdAnimal whether this species herds ({@code Animal} ∪ schooling fish — the mod's
 *                   class check); only these collapse into herd beings
 */
public record BeingReading(BeingId id, Being.Kind kind, String species, String name,
                           @Nullable String profession, boolean herdAnimal, Pos pos,
                           double distance, Being.Locomotion locomotion, boolean sneaking,
                           boolean watching, boolean aimedAt, boolean aggressive,
                           Being.Gear gear, Being.Activity activity) {
}
