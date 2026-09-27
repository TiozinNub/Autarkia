package dev.luizloyola.autarkia.mod.bp;

import dev.luizloyola.autarkia.compat.bp.RegistryDictionary;
import dev.luizloyola.autarkia.core.bp.BpCompiler;
import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import dev.luizloyola.autarkia.core.bp.Diagnostic;
import dev.luizloyola.autarkia.core.bp.Dictionary;
import dev.luizloyola.autarkia.core.bp.DictionaryFile;
import dev.luizloyola.autarkia.core.bp.DictionaryRules;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The blueprint library: {@code data/<ns>/autarkia/blueprint/**.bp} from datapacks and the jar,
 * and {@code config/autarkia/blueprints/**.bp} from the operator, config winning a clash. A broken
 * blueprint stays in the library carrying its diagnostics, so {@code bp list} can show it and
 * {@code bp check} can say why.
 *
 * <p>The dictionary is built from tags, and a datapack can change tags, so a reload rebuilds the
 * dictionary before it re-binds a single blueprint (decision: Luiz). A blueprint valid yesterday
 * can break today; that is what keeping broken entries is for.
 */
public final class Blueprints {

    private static final Logger LOGGER = LoggerFactory.getLogger("autarkia/blueprints");

    static final String DIRECTORY = "autarkia/blueprint";

    /** One blueprint in the library, whichever way its compile went. */
    public record Entry(String id, String origin, String text, Compiled compiled) {
    }

    private record Source(String id, String origin, String text) {
    }

    private static volatile Map<String, Source> packFiles = Map.of();
    private static volatile Map<String, Entry> library = Map.of();
    private static volatile Dictionary dictionary = Dictionary.EMPTY;
    private static volatile List<String> conflicts = List.of();

    private Blueprints() {
    }

    public static void init() {
        // addReloadListener for the reason AppearanceClient gives: the replacement API renamed its
        // method between the live targets, and this one is on all of them.
        ResourceManagerHelper.get(PackType.SERVER_DATA).addReloadListener(new Listener());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> rebuild());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> rebuild());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            library = Map.of();
            dictionary = Dictionary.EMPTY;
        });
    }

    public static Map<String, Entry> library() {
        return library;
    }

    public static Dictionary dictionary() {
        return dictionary;
    }

    /** By full id, or by path alone when exactly one namespace has it. */
    public static Optional<Entry> find(String id) {
        Map<String, Entry> now = library;
        Entry exact = now.get(id);
        if (exact != null) {
            return Optional.of(exact);
        }
        List<Entry> byPath = now.values().stream()
                .filter(entry -> entry.id().substring(entry.id().indexOf(':') + 1).equals(id)).toList();
        return byPath.size() == 1 ? Optional.of(byPath.get(0)) : Optional.empty();
    }

    /** Rebuilds the dictionary from the live registries and tags, then compiles every blueprint. */
    static void rebuild() {
        DictionaryRules.Derived derived = RegistryDictionary.build();
        dictionary = derived.dictionary();
        conflicts = derived.conflicts();
        conflicts.forEach(conflict -> LOGGER.warn("dictionary: two blocks for one form: {}", conflict));
        compileAll();
    }

    /** Re-reads the config directory against the dictionary already built — {@code bp reload}. */
    public static void reloadConfig() {
        compileAll();
    }

    private static void compileAll() {
        Map<String, Source> sources = new TreeMap<>(packFiles);
        for (Source source : configFiles()) {
            Source was = sources.put(source.id(), source);
            if (was != null) {
                LOGGER.info("blueprint {}: {} overrides {}", source.id(), source.origin(), was.origin());
            }
        }
        Map<String, Entry> compiled = new TreeMap<>();
        int broken = 0;
        for (Source source : sources.values()) {
            Compiled result = BpCompiler.compile(source.id(), source.text(), dictionary);
            compiled.put(source.id(), new Entry(source.id(), source.origin(), source.text(), result));
            if (!result.ok()) {
                broken++;
                LOGGER.warn("blueprint {} ({}) is broken:", source.id(), source.origin());
                result.diagnostics().stream().filter(Diagnostic::isError)
                        .forEach(d -> LOGGER.warn("  {}", d.summary()));
            }
        }
        library = Collections.unmodifiableMap(compiled);
        // The smoke boot reads this line: every shipped blueprint must bind on every node.
        LOGGER.info("blueprints: {} loaded, {} broken", compiled.size() - broken, broken);
    }

    public static Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("autarkia").resolve("blueprints");
    }

    /**
     * {@code config/autarkia/blueprints/house.bp} is {@code autarkia:house}; a file in a folder takes
     * the first folder as its namespace, so {@code mymod/tower.bp} overrides {@code mymod:tower}.
     */
    private static List<Source> configFiles() {
        Path root = configDirectory();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Source> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".bp")).sorted().toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                String path = relative.substring(0, relative.length() - ".bp".length());
                int slash = path.indexOf('/');
                String id = slash < 0 ? "autarkia:" + path : path.substring(0, slash) + ":" + path.substring(slash + 1);
                try {
                    sources.add(new Source(id, "config/autarkia/blueprints/" + relative,
                            Files.readString(file, StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    LOGGER.warn("blueprint {} could not be read: {}", relative, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("config/autarkia/blueprints could not be read: {}", e.getMessage());
        }
        return sources;
    }

    /** Writes the live dictionary where the offline checker can read it, if it changed; returns the file. */
    public static Path exportDictionary() throws IOException {
        String minecraft = FabricLoader.getInstance().getModContainer("minecraft")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        Path file = configDirectory().resolve("dictionary-" + minecraft + ".bpdict");
        Files.createDirectories(file.getParent());
        String header = "The blueprint dictionary of Minecraft " + minecraft
                + ", written by /autarkia bp dictionary and by every bp capture.\n"
                + "Not read by the game; scripts/bp-check.sh checks .bp files against it.";
        String text = DictionaryFile.write(dictionary, header);
        if (!Files.exists(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(text)) {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }
        return file;
    }

    public static List<String> conflicts() {
        return conflicts;
    }

    /** Reads the datapack files as a reload happens; the compile waits for tags, in {@link #rebuild}. */
    private static final class Listener implements SimpleSynchronousResourceReloadListener {
        private static final Identifier ID = Identifier.fromNamespaceAndPath("autarkia", "blueprints");

        @Override
        public Identifier getFabricId() {
            return ID;
        }

        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            Map<String, Source> found = new TreeMap<>();
            Map<Identifier, Resource> resources = manager.listResources(DIRECTORY,
                    path -> path.getPath().endsWith(".bp"));
            for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
                String path = entry.getKey().getPath();
                String id = entry.getKey().getNamespace() + ":"
                        + path.substring(DIRECTORY.length() + 1, path.length() - ".bp".length());
                try (Reader reader = entry.getValue().openAsReader()) {
                    StringBuilder text = new StringBuilder();
                    char[] buffer = new char[4096];
                    for (int read = reader.read(buffer); read >= 0; read = reader.read(buffer)) {
                        text.append(buffer, 0, read);
                    }
                    found.put(id, new Source(id, entry.getValue().sourcePackId(), text.toString()));
                } catch (IOException e) {
                    LOGGER.warn("blueprint {} could not be read: {}", id, e.getMessage());
                }
            }
            packFiles = Collections.unmodifiableMap(found);
        }
    }
}
