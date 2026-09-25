package dev.luizloyola.autarkia.core.bp;

/** A bare id is {@code minecraft:} (decision: Luiz) — for blocks and materials alike. */
public final class Ids {

    private Ids() {
    }

    public static String qualify(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    /** The shortest way to write it back: vanilla ids lose their namespace. */
    public static String brief(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    public static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(0, colon) : "minecraft";
    }

    public static String path(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}
