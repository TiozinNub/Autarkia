package dev.luizloyola.autarkia.core.config;

/**
 * The live configuration: one process-wide, atomically-swapped {@link AutarkiaConfig} every tunable
 * reads through, rather than a config threaded down hundreds of call sites. Safe because what is
 * shared is immutable — {@link #install} swaps the reference, never a value, so a reload lands
 * mid-tick unobserved; {@code volatile} because pathfinding reads off the server thread.
 *
 * <p>A read is a volatile read plus an array index: read on use rather than caching into a field,
 * and {@code /autarkia config reload} takes effect everywhere at once. Tests start at
 * {@link AutarkiaConfig#DEFAULTS}; one that installs something should {@link #reset} afterwards.
 */
public final class Config {

    private static volatile AutarkiaConfig current = AutarkiaConfig.DEFAULTS;

    private Config() {
    }

    /** The configuration in force right now. Never null. */
    public static AutarkiaConfig get() {
        return current;
    }

    /** Swaps in a new configuration; every subsequent read sees it whole. */
    public static void install(AutarkiaConfig config) {
        current = config == null ? AutarkiaConfig.DEFAULTS : config;
    }

    /** Back to {@link AutarkiaConfig#DEFAULTS} — teardown for tests, and the "lost the file" path. */
    public static void reset() {
        current = AutarkiaConfig.DEFAULTS;
    }
}
