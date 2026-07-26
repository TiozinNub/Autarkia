package dev.luizloyola.autarkia.core.brain.sense;

import dev.luizloyola.autarkia.core.person.PersonId;

/**
 * One raw observation of one person-shaped body, as the mod's eyes deliver it to the
 * {@link PeerSensorCore}: the observable facts plus the classified {@link Peer.Activity}. Which
 * channel it arrived through and how far to trust it over time is the SENSOR's business — a reading
 * carries no awareness of its own.
 */
public record PeerReading(PersonId id, String name, Pos pos, double distance,
                          boolean sneaking, boolean watching, Peer.Activity activity) {
}
