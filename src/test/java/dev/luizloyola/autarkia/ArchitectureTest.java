package dev.luizloyola.autarkia;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.arch.SourceTree;
import dev.luizloyola.anima.arch.SourceTree.JavaSource;
import dev.luizloyola.anima.arch.SourceTree.Line;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces the layering CLAUDE.md describes, over Autarkia's own source.
 *
 * <p>The scanner is Anima's ({@link SourceTree}), so only the rule TABLE is duplicated — and it has
 * to be per mod: Autarkia's {@code core/} may name Anima's {@code core/}, the point of a library,
 * but not Anima's {@code compat/}, whose version matrix a consumer would then take on. That rule
 * landed with one violation already in the tree.
 */
class ArchitectureTest {

    /** Where the branch source lives — handed in by the {@code test} task, never guessed. */
    private static final String SOURCE_ROOT_PROPERTY = "autarkia.arch.sourceRoot";

    private static final String ROOT = "dev.luizloyola.autarkia";
    private static final String CORE = ROOT + ".core";
    private static final String ANIMA = "dev.luizloyola.anima";

    /** Everything {@code core/} may not name — see Anima's copy of this list for the reasoning. */
    private static final List<String> GAME_PACKAGES = List.of(
            "net.minecraft.", "net.fabricmc.", "com.mojang.", "org.spongepowered.", "org.lwjgl.");

    /**
     * The layers {@code core/} sits below — Autarkia's own, and Anima's, which sit at the
     * same height. {@code dev.luizloyola.anima.core} is absent: taking the library's
     * pure simulation is what a consumer is for.
     */
    private static final List<String> OUTER_LAYERS = List.of(
            ROOT + ".compat.", ROOT + ".mod.", ROOT + ".mixin.",
            ANIMA + ".compat.", ANIMA + ".mod.", ANIMA + ".mixin.");

    /**
     * Public API only is a HARD constraint: Sinytra Connector re-implements the public surface on
     * NeoForge and nothing else, so an {@code impl}/{@code mixin} import boots on Fabric and dies on
     * Connector — found by a player, on the loader nobody here runs by default.
     */
    private static final List<String> FABRIC_INTERNALS =
            List.of("net.fabricmc.fabric.impl.", "net.fabricmc.fabric.mixin.");

    /**
     * A field initialiser that reads an entity id. From 26.2 the id is handed out by the LEVEL
     * ({@code Entity.<init>} calls {@code level.getNextEntityId()}), and only a {@code ServerLevel}
     * returns a real one — the base {@code Level} returns 0, so a client entity stays unassigned
     * until the spawn packet calls {@code setId}, which happens AFTER every field initialiser has
     * run. {@code getId()} throws on that 0.
     *
     * <p>Matches one line, so a call wrapped onto a continuation line slips through. That is the
     * cheap 90%: the case this was written for read {@code = personalBoard(getId())} and cost a
     * released build.
     */
    private static final Pattern ID_IN_FIELD_INITIALIZER = Pattern.compile(
            "^\\s*(?:private|protected|public|static|final)\\b.*=.*\\bgetId\\s*\\(\\s*\\)");

    private static final SourceTree TREE = SourceTree.fromSystemProperty(SOURCE_ROOT_PROPERTY);

    @Test
    @DisplayName("core/ never names Minecraft, the loader, or anything else that arrives with them")
    void coreIsPureSimulation() {
        List<String> violations = new ArrayList<>();
        for (JavaSource file : TREE.inPackage(CORE)) {
            for (String banned : GAME_PACKAGES) {
                for (Line line : file.mentions(banned)) {
                    violations.add(SourceTree.at(file, line));
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> SourceTree.report(
                "core/ is the version-independent half of the mod and must stay headless-testable: "
                        + "the type belongs behind a compat/ facade named for what the agent needs",
                violations));
    }

    @Test
    @DisplayName("core/ never names the layers above it, in either mod")
    void coreDoesNotReachUpwards() {
        List<String> violations = new ArrayList<>();
        for (JavaSource file : TREE.inPackage(CORE)) {
            for (String layer : OUTER_LAYERS) {
                for (Line line : file.mentions(layer)) {
                    violations.add(SourceTree.at(file, line));
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> SourceTree.report(
                "core/ may take Anima's core/ and nothing else above it — a compat/ type in a "
                        + "simulation class drags the version matrix into the headless half",
                violations));
    }

    @Test
    @DisplayName("Stonecutter directives live only in compat/ and mixin/")
    void versionSpecificCodeIsQuarantined() {
        List<String> violations = new ArrayList<>();
        for (JavaSource file : TREE.all()) {
            if (file.path().contains("/compat/") || file.path().contains("/mixin/")) {
                continue;
            }
            for (Line line : file.directives()) {
                violations.add(SourceTree.at(file, line));
            }
        }
        assertTrue(violations.isEmpty(), () -> SourceTree.report(
                "a `//?` outside compat/ or mixin/ spreads the version matrix across the codebase: "
                        + "every file carrying one has to be re-read on every Minecraft update, "
                        + "which is precisely what quarantining them buys",
                violations));
    }

    @Test
    @DisplayName("nothing reaches into Fabric API's internals")
    void onlyPublicFabricApiIsUsed() {
        List<String> violations = new ArrayList<>();
        for (JavaSource file : TREE.all()) {
            for (String internal : FABRIC_INTERNALS) {
                for (Line line : file.mentions(internal)) {
                    violations.add(SourceTree.at(file, line));
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> SourceTree.report(
                "Fabric's impl/ and mixin/ packages have no Connector equivalent — find the public "
                        + "API that exposes the same thing, or do it with an ordinary Mixin",
                violations));
    }

    @Test
    @DisplayName("only Replies speaks to a command source")
    void everyCommandReplyGoesThroughReplies() {
        List<String> violations = new ArrayList<>();
        for (JavaSource file : TREE.all()) {
            for (String method : List.of("sendSuccess", "sendFailure")) {
                for (Line line : file.mentions(method)) {
                    violations.add(SourceTree.at(file, line));
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> SourceTree.report(
                "Replies.send/fail (Anima's) is the one choke point that stamps a line with the "
                        + "agent it ran as ([as John] …) — Autarkia has no exemption at all, since "
                        + "Replies itself lives in the library",
                violations));
    }

    @Test
    @DisplayName("no field initialiser reads an entity id")
    void entityIdIsNotReadDuringConstruction() {
        List<String> violations = new ArrayList<>();
        for (JavaSource file : TREE.all()) {
            for (Line line : matching(file, ID_IN_FIELD_INITIALIZER)) {
                violations.add(SourceTree.at(file, line));
            }
        }
        assertTrue(violations.isEmpty(), () -> SourceTree.report(
                "a field initialiser runs before the level assigns an entity id, so getId() throws "
                        + "on the client from 26.2 on and the spawn packet disconnects the player — "
                        + "read the id in a method, or drop it if the argument was never used",
                violations));
    }

    /**
     * Lines of {@code file} that {@code rule} matches, searched over the comment-blanked
     * {@link JavaSource#code()} so prose describing a rule never breaks it, and numbered against
     * the raw file so the failure reads as what was written.
     */
    private static List<Line> matching(JavaSource file, Pattern rule) {
        String[] code = file.code().split("\n", -1);
        String[] raw = file.text().split("\n", -1);
        List<Line> found = new ArrayList<>();
        for (int i = 0; i < code.length; i++) {
            if (rule.matcher(code[i]).find()) {
                found.add(new Line(i + 1, raw[i].strip()));
            }
        }
        return found;
    }
}
