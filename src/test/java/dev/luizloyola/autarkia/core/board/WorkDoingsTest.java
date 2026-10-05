package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.history.DoingLines;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.tree.TreeFelling;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Autarkia's doings have their words, and so does everything a work item puts in their slots. The
 * compiler makes every work item name a doing; this makes every one of them say something.
 */
class WorkDoingsTest {

    private static final Map<String, String> EN =
            DoingLines.load(WorkDoingsTest.class, "/assets/autarkia/lang/en_us.json");

    static {
        Objects.requireNonNull(WorkDoings.GATHERING); // declared, as AutarkiaMod declares them
    }

    @Test
    void everyRememberedDoingOfAutarkiasHasItsLines() {
        assertEquals(List.of(), DoingLines.problems("autarkia.", EN));
    }

    @Test
    void everySlotAutarkiaFillsHasWords() {
        for (Slot slot : List.of(WorkDoings.goods(Stock.LOGS), WorkDoings.goods(Stock.AXES),
                WorkDoings.FOR_THE_STORES, WorkDoings.cleared(TreeFelling.INSTANCE))) {
            assertEquals(Slot.Type.LANG, slot.type(), slot.encode());
            assertTrue(EN.containsKey(slot.value()), slot.value() + " has no line in en_us");
        }
    }

    /**
     * A deed names whichever spec its work item carries, Anima's or Autarkia's. Specs are held in
     * static fields of {@code core/} classes, so initialising those registers every one; specs a
     * test registered, and literals the game names, are left out by where they came from.
     */
    @Test
    void everyRegisteredSpecHasWords() throws Exception {
        Set<CodeSource> shipped = Set.of(source(WorkDoings.class), source(ItemSpec.class));
        registerSpecsIn(source(WorkDoings.class), "dev/luizloyola/autarkia/core/");
        registerSpecsIn(source(ItemSpec.class), "dev/luizloyola/anima/core/");

        List<String> missing = new ArrayList<>();
        for (String name : new TreeSet<>(ItemSpec.names())) {
            ItemSpec spec = ItemSpec.byName(name).orElseThrow();
            boolean ours = shipped.contains(source(spec.matcher().getClass().getNestHost()));
            if (ours && ItemSpec.literalIds(spec).isEmpty() && !EN.containsKey("autarkia.goods." + name)) {
                missing.add("autarkia.goods." + name);
            }
        }
        assertTrue(ItemSpec.byName("ready_food").isPresent(), "Anima's specs were not loaded");
        assertEquals(List.of(), missing, "specs a deed would name by a raw key");
    }

    private static CodeSource source(Class<?> type) {
        return type.getProtectionDomain().getCodeSource();
    }

    private static void registerSpecsIn(CodeSource from, String prefix) throws Exception {
        Path root = Path.of(from.getLocation().toURI());
        List<String> files = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root.resolve(prefix))) {
                walk.map(p -> root.relativize(p).toString().replace(File.separatorChar, '/'))
                        .forEach(files::add);
            }
        } else {
            try (JarFile jar = new JarFile(root.toFile())) {
                jar.stream().map(JarEntry::getName).filter(n -> n.startsWith(prefix)).forEach(files::add);
            }
        }
        ClassLoader loader = WorkDoingsTest.class.getClassLoader();
        for (String file : files) {
            if (!file.endsWith(".class")) {
                continue;
            }
            String name = file.substring(0, file.length() - ".class".length()).replace('/', '.');
            Class<?> type = Class.forName(name, false, loader);
            if (Arrays.stream(type.getDeclaredFields()).anyMatch(WorkDoingsTest::holdsSpec)) {
                Class.forName(name, true, loader);
            }
        }
    }

    private static boolean holdsSpec(Field field) {
        return Modifier.isStatic(field.getModifiers()) && field.getType() == ItemSpec.class;
    }

    @Test
    void aLiteralSpecIsNamedByTheGameThroughItsFirstItem() {
        ItemSpec stones = ItemSpec.anyOf(Set.of("minecraft:stone", "minecraft:andesite"));

        assertEquals(Slot.item("minecraft:andesite"), WorkDoings.goods(stones),
                "the gather command's literal has no lang key of ours — the game names the item");
    }
}
