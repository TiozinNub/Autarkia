package dev.luizloyola.autarkia.core.brain.act;

/**
 * Where the climb is in its lifecycle — one pillar STEP at a time ({@link Scaffolder#up}),
 * the breaker/consumer lifecycle shape applied to gaining height.
 */
public enum ScaffoldState {
    IDLE,
    /** Mid-step: jumping, placing the block underneath, landing. */
    RISING,
    /** The last step landed: she stands one block higher, on a block from her own pack. */
    RISEN,
    /** The last step died: no headroom, the jump never cleared, or the placement failed. */
    FAILED
}
