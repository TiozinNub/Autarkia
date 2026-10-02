package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.Dictionary;
import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The stands a plan's proved order takes outside its drawing, which is all of the proof a site
 * needs, kept per plan as the proof reads it. The proof reads no world and no placement, and of a
 * block everything but its name and its item, so the same house in another wood or bed colour
 * proves the same. Proving was 90% of siting on the forest (2026-10-02, 20 lone settlers), once per
 * party per try.
 */
final class OutsideStands {

    static final int CAPACITY = 64;
    static final OutsideStands SHARED = new OutsideStands(CAPACITY);

    private final Map<Key, List<Cell>> cache;
    private @Nullable Dictionary dict;
    private int proofs;

    OutsideStands(int capacity) {
        cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, List<Cell>> eldest) {
                return size() > capacity;
            }
        };
    }

    synchronized List<Cell> of(BuildPlan plan, Dictionary dict) {
        // A datapack reload rebuilds the dictionary, and a tag may have moved under every entry.
        if (dict != this.dict) {
            cache.clear();
            this.dict = dict;
        }
        Key key = Key.of(plan, dict);
        List<Cell> stands = cache.get(key);
        if (stands == null) {
            proofs++;
            stands = of(BuildOrder.prove(plan, dict), plan);
            cache.put(key, stands);
        }
        return stands;
    }

    /** How many proofs this has run. */
    synchronized int proofs() {
        return proofs;
    }

    static List<Cell> of(BuildOrder.Result proved, BuildPlan plan) {
        Set<Cell> outside = new LinkedHashSet<>();
        for (BuildOrder.Placed placed : proved.order()) {
            Cell stand = placed.stand();
            if (stand.x() < 0 || stand.z() < 0 || stand.x() >= plan.width() || stand.z() >= plan.depth()) {
                outside.add(new Cell(0, stand.x(), stand.z()));
            }
        }
        return List.copyOf(outside);
    }

    /**
     * A block as {@link Sections} and {@link BuildOrder} may read it. Its name becomes the order it
     * first appears in, which keeps every "the same block as" a fixture's halves ask.
     */
    private record Feature(int block, Map<String, String> props, @Nullable Map<String, List<String>> properties,
                           @Nullable Map<String, String> defaults, boolean solid, boolean obstructs, int light,
                           boolean falls, @Nullable Set<String> tags, boolean wallTwin) {
    }

    /** Each cell's kind and feature, the features in order of first use, and the plan's bounds. */
    private record Key(int width, int depth, int minLayer, int maxLayer, int[] cells, List<Feature> features) {

        static Key of(BuildPlan plan, Dictionary dict) {
            Map<String, Integer> blocks = new HashMap<>();
            Map<Outcome, Integer> byState = new HashMap<>();
            Map<Feature, Integer> byFeature = new HashMap<>();
            List<Feature> features = new ArrayList<>();
            Set<String> twins = Set.copyOf(dict.wallTwins().values());
            int[] cells = new int[(plan.maxLayer() - plan.minLayer() + 1) * plan.depth() * plan.width()];
            int i = 0;
            for (int layer = plan.minLayer(); layer <= plan.maxLayer(); layer++) {
                for (int z = 0; z < plan.depth(); z++) {
                    for (int x = 0; x < plan.width(); x++) {
                        Outcome state = plan.state(layer, x, z);
                        int id = 0;
                        if (state != null) {
                            Integer known = byState.get(state);
                            if (known == null) {
                                Integer block = blocks.get(state.block());
                                if (block == null) {
                                    block = blocks.size();
                                    blocks.put(state.block(), block);
                                }
                                BlockInfo info = dict.block(state.block()).orElse(null);
                                Feature feature = info == null
                                        ? new Feature(block, state.props(), null, null, false, false, 0, false, null, false)
                                        : new Feature(block, state.props(), info.properties(), info.defaults(),
                                                info.solid(), info.obstructs(), info.light(), info.falls(), info.tags(),
                                                twins.contains(info.id()));
                                known = byFeature.get(feature);
                                if (known == null) {
                                    features.add(feature);
                                    known = features.size();
                                    byFeature.put(feature, known);
                                }
                                byState.put(state, known);
                            }
                            id = known;
                        }
                        cells[i++] = id * BuildPlan.CellKind.values().length + plan.kind(layer, x, z).ordinal();
                    }
                }
            }
            return new Key(plan.width(), plan.depth(), plan.minLayer(), plan.maxLayer(), cells, List.copyOf(features));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && width == k.width && depth == k.depth && minLayer == k.minLayer
                    && maxLayer == k.maxLayer && Arrays.equals(cells, k.cells) && features.equals(k.features);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(cells) + features.hashCode();
        }
    }
}
