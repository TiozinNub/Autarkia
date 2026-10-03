package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What kind of place a registered source is found at — trees for logs, stone patches for stone —
 * so an expedition searching for one knows which glimpses mean it. Registered beside the producer.
 */
public final class SourceKinds {

    private static final Map<ItemSpec, PoiKind> KINDS = new ConcurrentHashMap<>();

    private SourceKinds() {
    }

    public static void register(ItemSpec source, PoiKind kind) {
        KINDS.put(source, kind);
    }

    public static Optional<PoiKind> of(ItemSpec source) {
        return Optional.ofNullable(KINDS.get(source));
    }
}
