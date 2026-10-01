package dev.luizloyola.autarkia.mod.direction;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Acts;
import dev.luizloyola.anima.core.brain.gate.Gate;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.JournalService;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.store.Depot;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.autarkia.compat.inv.ItemIds;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.direction.Direction;
import dev.luizloyola.autarkia.core.direction.DirectionId;
import dev.luizloyola.autarkia.core.direction.Evolution;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.Node;
import dev.luizloyola.autarkia.core.direction.NodeKind;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.core.direction.PartyView;
import dev.luizloyola.autarkia.core.direction.Tree;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 4's host: the tree loaded from datapacks, each party's Directions beaten on a slow cadence,
 * and Anima's gate answered from the nodes a body has reached. Entity-free, like the boards it posts
 * to — a party keeps climbing while every member is unloaded.
 */
public final class Directions {

    private static final Logger LOGGER = LoggerFactory.getLogger("autarkia/directions");

    /**
     * Ticks between one party's beats. Much slower than the board's 40: a Direction reads chests,
     * and a settlement's goals do not change by the second.
     */
    public static final int BEAT_TICKS = 200;

    private static volatile NodeFiles.Read files = NodeFiles.Read.NONE;
    private static volatile Tree tree = Tree.EMPTY;

    /** Why the datapack tree was last refused, for the readout; empty when it loaded. */
    private static volatile List<String> refused = List.of();

    private static @Nullable MinecraftServer live;

    private Directions() {
    }

    public static void init() {
        // addReloadListener for the reason AppearanceClient gives: the replacement API renamed its
        // method between the live targets, and this one is on all of them.
        ResourceManagerHelper.get(PackType.SERVER_DATA).addReloadListener(new Listener());
        // Tags are bound only once a reload has finished, so the files are turned into a tree here
        // and not inside the listener.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            live = server;
            rebuild();
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> rebuild());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> live = null);
        ServerTickEvents.END_SERVER_TICK.register(Directions::tick);
        PartyBoards.onClosed(Directions::closed);
        Gate.install(new Answers());
        Depot.install(Directions::depotOf);
    }

    public static Tree tree() {
        return tree;
    }

    public static List<String> refused() {
        return refused;
    }

    /**
     * Builds the tree from the files the last reload read. A table that breaks a rule is refused
     * whole and the last good one stays in force — a half-loaded tree would gate some things and
     * not others with nothing to say why.
     */
    private static void rebuild() {
        NodeFiles.Read read = files;
        List<String> warnings = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        for (NodeFiles.NodeFile file : read.files()) {
            nodes.add(NodeFiles.resolve(file, warnings));
        }
        Tree.Built built = Tree.build(nodes, ItemIds.all(), key -> Acts.byKey(key).isPresent());
        warnings.addAll(built.warnings());
        warnings.forEach(warning -> LOGGER.warn("{}", warning));
        List<String> errors = new ArrayList<>(read.errors());
        errors.addAll(built.errors());
        if (errors.isEmpty()) {
            tree = Objects.requireNonNull(built.tree());
            refused = List.of();
            LOGGER.info("the tree has {} node(s)", tree.nodes().size());
        } else {
            refused = List.copyOf(errors);
            errors.forEach(error -> LOGGER.error("{}", error));
            LOGGER.error("the tree was refused; the last good one stays in force");
        }
        Gate.changed();
    }

    // ── the beat ────────────────────────────────────────────────────────────────────────────

    private static void tick(MinecraftServer server) {
        if (server != live || tree.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        DirectionsData data = DirectionsData.get(server);
        for (Map.Entry<PartyId, PartyProgress> entry : List.copyOf(data.parties().entrySet())) {
            PartyId party = entry.getKey();
            if (Math.floorMod(now, BEAT_TICKS) == Math.floorMod(party.value().hashCode(), BEAT_TICKS)) {
                beat(server, data, party, entry.getValue());
            }
        }
    }

    private static void beat(MinecraftServer server, DirectionsData data, PartyId party,
                             PartyProgress progress) {
        List<AgentId> members = PartyData.get(server).members(party);
        if (members.isEmpty()) {
            return; // a party nobody is in any more: nothing to post for, nobody to tell
        }
        Home before = progress.home();
        PartyBoard board = PartyBoards.of(server, party);
        Evolution.Outcome outcome = Evolution.beat(tree, progress, view(server, party, progress), board);
        JournalService journal = Journals.of(server);
        for (Evolution.Posted posted : outcome.posted()) {
            tell(journal, members, "posted #" + posted.handle() + " " + posted.project().describe()
                    + " — " + describe(posted.direction()));
        }
        for (Evolution.Withdrawn withdrawn : outcome.withdrawn()) {
            tell(journal, members, "withdrew " + withdrawn.project().describe() + " — "
                    + describe(withdrawn.direction()) + " holds");
        }
        for (DirectionId completed : outcome.completed()) {
            tell(journal, members, "completed " + completed);
        }
        for (String reached : outcome.reached()) {
            tell(journal, members, "the party " + tree.node(reached).map(Directions::reachedPhrase)
                    .orElse("reached " + reached));
        }
        if (!outcome.posted().isEmpty() || !outcome.withdrawn().isEmpty()) {
            PartyBoards.touch(server);
        }
        if (outcome.changedProgress() || !Objects.equals(before, progress.home())) {
            data.setDirty();
        }
        boolean grew = false;
        for (AgentId member : members) {
            grew |= data.personReach(member, progress.reached());
        }
        if (grew || !outcome.reached().isEmpty()) {
            Gate.changed();
        }
    }

    /**
     * A party's board closed finished work: each line whose work it was hears now, while the
     * project still holds what it learned. Saved with the progress, so a restart loses nothing.
     */
    private static void closed(MinecraftServer server, PartyId party, List<Project> finished) {
        if (server != live || tree.isEmpty()) {
            return;
        }
        DirectionsData data = DirectionsData.get(server);
        data.find(party).ifPresent(progress -> {
            if (!Evolution.collect(tree, progress, view(server, party, progress), finished).isEmpty()) {
                data.setDirty();
            }
        });
    }

    public static PartyView view(MinecraftServer server, PartyId party, PartyProgress progress) {
        return new HomeView(server, party, progress, PartyData.get(server).members(party).size());
    }

    /** One journal line to each member, never N copies to one. */
    private static void tell(JournalService journal, List<AgentId> members, String line) {
        for (AgentId member : members) {
            journal.record(member, Category.PROJECT, "directions", line);
        }
    }

    // ── what an operator may do ────────────────────────────────────────────────────────────

    /** A party gains the node and what it needs above it; so do its members. What was added. */
    public static Set<String> grant(MinecraftServer server, PartyId party, String node) {
        DirectionsData data = DirectionsData.get(server);
        PartyProgress progress = data.progress(party);
        Set<String> added = tree.grantClosure(node, progress.reached());
        added.forEach(progress::reach);
        for (AgentId member : PartyData.get(server).members(party)) {
            data.personReach(member, added);
        }
        data.setDirty();
        Gate.changed();
        return added;
    }

    public static Set<String> grant(MinecraftServer server, AgentId who, String node) {
        DirectionsData data = DirectionsData.get(server);
        Set<String> added = tree.grantClosure(node, data.reachedBy(who));
        data.personReach(who, added);
        Gate.changed();
        return added;
    }

    /**
     * Takes the node and everything below it back from a party and its members — and the
     * checkpoints that earned them, or the next beat would simply reach them again. The one way
     * anything leaves a reached set, and never the game's.
     */
    public static Set<String> revoke(MinecraftServer server, PartyId party, String node) {
        DirectionsData data = DirectionsData.get(server);
        PartyProgress progress = data.progress(party);
        Set<String> gone = new LinkedHashSet<>();
        gone.add(node);
        gone.addAll(tree.descendantsOf(node));
        gone.retainAll(progress.reached());
        gone.forEach(progress::revoke);
        for (String id : gone) {
            tree.node(id).ifPresent(taken -> {
                taken.directions().forEach(direction -> progress.forget(direction.id()));
                Optional.ofNullable(taken.requirements().key()).ifPresent(progress::forget);
                taken.requirements().pool().forEach(progress::forget);
            });
        }
        for (AgentId member : PartyData.get(server).members(party)) {
            data.personRevoke(member, gone);
        }
        data.setDirty();
        Gate.changed();
        return gone;
    }

    public static Set<String> revoke(MinecraftServer server, AgentId who, String node) {
        DirectionsData data = DirectionsData.get(server);
        Set<String> gone = new LinkedHashSet<>();
        gone.add(node);
        gone.addAll(tree.descendantsOf(node));
        gone.retainAll(data.reachedBy(who));
        data.personRevoke(who, gone);
        Gate.changed();
        return gone;
    }

    /**
     * A new HOME, or none. The work the Directions had out was for the old plot and yard, so it is
     * withdrawn; the next beat posts again for the new one.
     */
    public static void home(MinecraftServer server, PartyId party, @Nullable Home home) {
        DirectionsData data = DirectionsData.get(server);
        PartyProgress progress = data.progress(party);
        // Found by content against the HOME being left, since its work names that plot and yard.
        // An in-memory map of it was empty after a restart, and a HOME moved then left the old
        // yard's gather and clearing running (2026-09-25).
        PartyBoard board = PartyBoards.of(server, party);
        List<Project> old = tree.isEmpty() || progress.home() == null ? List.of()
                : Evolution.ownWork(tree, progress, view(server, party, progress), board);
        progress.home(home);
        data.setDirty();
        if (!old.isEmpty()) {
            for (Project project : old) {
                board.handleOf(project).ifPresent(board::cancel);
            }
            PartyBoards.touch(server);
        }
    }

    // ── words for the journal ──────────────────────────────────────────────────────────────

    /** {@code wood (the Wood Age)} — journal text, which is English by design. */
    private static String describe(Direction direction) {
        return direction.line() + " (" + tree.node(direction.id().node()).map(Directions::label)
                .orElse(direction.id().node()) + ")";
    }

    /** {@code the Stone Age}, or a side node's own name. */
    public static String label(Node node) {
        String name = plainName(node.id());
        return node.kind() == NodeKind.CORE ? "the " + name + " Age" : name;
    }

    private static String reachedPhrase(Node node) {
        return node.kind() == NodeKind.CORE ? "reached " + label(node) : "learned " + label(node);
    }

    private static String refusal(Node node) {
        return node.kind() == NodeKind.CORE ? "not in " + label(node) : "has not learned " + label(node);
    }

    /** {@code autarkia:wool_and_milk} → {@code Wool and milk}: the fallback for a node with no lang key. */
    public static String plainName(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        path = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
        return path.isEmpty() ? id : path.substring(0, 1).toUpperCase(Locale.ROOT) + path.substring(1);
    }

    // ── the gate ────────────────────────────────────────────────────────────────────────────

    /**
     * What a body has reached: its own set, and its party's — a settler who just joined a party
     * that knows more is let in before the next beat writes it down.
     */
    static Set<String> reachedBy(MinecraftServer server, AgentId who) {
        DirectionsData data = DirectionsData.get(server);
        Set<String> reached = new LinkedHashSet<>(data.reachedBy(who));
        PartyData.get(server).currentPartyOf(who).flatMap(data::find)
                .ifPresent(progress -> reached.addAll(progress.reached()));
        return reached;
    }

    /** Where a body's goods go: its party's HOME yard, and nowhere before it has one. */
    private static Optional<Pos> depotOf(AgentId body) {
        MinecraftServer server = live;
        if (server == null) {
            return Optional.empty();
        }
        return PartyData.get(server).currentPartyOf(body)
                .flatMap(DirectionsData.get(server)::find)
                .map(PartyProgress::home)
                .map(Home::yard);
    }

    /** Anima's two questions, answered from the node table. */
    private static final class Answers implements Gate.Policy {
        @Override
        public Optional<String> refuseItem(AgentId body, String itemId) {
            MinecraftServer server = live;
            Tree now = tree;
            if (server == null || !now.gatesItem(itemId)) {
                return Optional.empty();
            }
            return now.lacksForItem(itemId, reachedBy(server, body)).map(Directions::refusal);
        }

        @Override
        public Optional<String> refuseAct(AgentId body, Act act) {
            MinecraftServer server = live;
            Tree now = tree;
            if (server == null || !now.gatesAct(act.key())) {
                return Optional.empty();
            }
            return now.lacksForAct(act.key(), reachedBy(server, body)).map(Directions::refusal);
        }
    }

    /** Reads the node files as a reload happens; {@link #rebuild} turns them into a tree after. */
    private static final class Listener implements SimpleSynchronousResourceReloadListener {
        private static final Identifier ID = Identifier.fromNamespaceAndPath("autarkia", "directions");

        @Override
        public Identifier getFabricId() {
            return ID;
        }

        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            files = NodeFiles.read(manager);
        }
    }
}
