package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A real worldgen fancy oak, block for block. Vanilla scatters branch logs two and three cells
 * clear of any other wood, which orphaned four airborne fragments (7 of its 17 logs) as strays
 * the chop plan silently ignored — gaps a hand-built shape would never dare. The in-world twin
 * stands cloned at (60, -60, 140).
 */
class GauntletFancyOakTest {

    /** Lifts the scan above {@link FakeProbe#GROUND_Y} (the world floor was y -60). */
    private static final int LIFT = FakeProbe.GROUND_Y + 1 + 60;

    @Test
    void theRealFancyOakIsOneTreeWithNoStrayWood() {
        FakeProbe probe = new FakeProbe();
        Map<Pos, BlockKind> mass = new LinkedHashMap<>();
        int logs = 0;
        try (BufferedReader scan = new BufferedReader(new InputStreamReader(
                GauntletFancyOakTest.class.getResourceAsStream("/tree/gauntlet-fancy-oak.txt"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = scan.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cell = line.split(" ");
                BlockKind kind = cell[0].equals("L") ? BlockKind.LOG : BlockKind.LEAVES;
                logs += kind == BlockKind.LOG ? 1 : 0;
                int x = Integer.parseInt(cell[1]);
                int y = Integer.parseInt(cell[2]) + LIFT;
                int z = Integer.parseInt(cell[3]);
                probe.set(x, y, z, kind);
                mass.put(new Pos(x, y, z), kind);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertEquals(17, logs, "the fixture is the whole scan");

        SplitReport report = SplitReport.of(mass, probe);

        assertEquals(1, report.trees().size(), "one oak, however scattered its limbs");
        TreeShape.Trunk oak = report.trees().get(0);
        assertEquals(17, oak.logCount(), "every log is the tree's, airborne fragments included");
        assertTrue(report.strayLogs().isEmpty(), "no wood goes silently unfellable");
        List<Pos> fragment = List.of(new Pos(156, -50 + LIFT, 160), new Pos(157, -50 + LIFT, 161),
                new Pos(159, -49 + LIFT, 163));
        for (Pos cell : fragment) {
            assertTrue(oak.branches().contains(cell),
                    "the farthest fragments are branches: " + cell);
        }
    }
}
