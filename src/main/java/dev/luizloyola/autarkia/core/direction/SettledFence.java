package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.nav.HandsOff;
import java.util.List;

/**
 * What settled ground keeps whole (docs/superpowers/specs/2026-09-28-bridging-design.md, safety rule
 * 6). Nothing is laid or cut on a settled spot — a station, a structure's pad, a flatten site. Inside
 * a party's HOME a deck or a pillar may cross a natural gap, but nothing is dug (Luiz, 2026-10-02:
 * Ruth's ravine inside her own HOME had no route across).
 */
public final class SettledFence {

    private SettledFence() {
    }

    /**
     * One fence for a walk and a dig alike: a dig cuts, so it keeps out of
     * {@link HandsOff#barsCut} — HOME and the spots both.
     *
     * @param home  HOME's rows, each {@code {x1, z1, x2, z2}}
     * @param spots the settled spots' boxes, the same shape
     */
    public static HandsOff of(List<int[]> home, List<int[]> spots) {
        return HandsOff.columns(spots, home);
    }
}
