package dev.luizloyola.autarkia.mod.log;

import dev.luizloyola.autarkia.core.log.Entry;
import dev.luizloyola.autarkia.core.log.JournalService;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.AutarkiaMod;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * The durable half of the debug log: a {@link JournalService} subscriber writing every entry to a
 * per-person file, {@code logs/autarkia/person-<UUID>-<NAME>-<TIMESTAMP>.log}. The service's rings
 * are the ephemeral recall behind {@code /autarkia log}; this is the keep-forever archive.
 *
 * <p><b>Off the tick, batched.</b> {@link #onEntry} runs on the server thread and only resolves the
 * name (a directory read, which must stay server-thread) and queues; a daemon thread drains every
 * {@link #FLUSH_SECONDS}, grouped by person, one append per file per cycle. A crash loses at most
 * the last cadence; a clean {@code SERVER_STOPPING} loses nothing (see {@link #close}).
 *
 * <p><b>Bounded open files.</b> At most {@link #MAX_OPEN_FILES} writers stay open (access-ordered
 * LRU); a colder handle is closed and reopened in append mode, so thousands of Persons cannot
 * exhaust file descriptors. Past the constructor's field setup everything runs on the writer
 * thread; the queue and the name cache are the only cross-thread state, both concurrent.
 *
 * <p>{@code <TIMESTAMP>} is the run's wall-clock start, shared by every file this boot, and the
 * service is rebuilt each boot, so each run mints a fresh set. A file keeps the name it opened
 * with — mid-run renames are not tracked, the UUID identifies it regardless. Cleanup of old runs
 * and a {@code graveyard/} for the dead are deferred.
 */
public final class JournalFileSink {

    /** How often the writer thread drains the queue and flushes — the batching cadence. */
    private static final long FLUSH_SECONDS = 2;

    /** Most per-person files open at once; the coldest is closed (reopened on demand) past this. */
    private static final int MAX_OPEN_FILES = 32;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final MinecraftServer server;
    private final Path dir;
    private final String runStamp;
    /** id → filesystem-safe name, resolved once on the server thread (a directory read lives there). */
    private final Map<PersonId, String> names = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService writer;
    /** Open writers, access-ordered so the eldest entry is the least-recently-written. Writer-thread only. */
    private final LinkedHashMap<PersonId, BufferedWriter> handles;

    private record Pending(PersonId id, Entry entry) {}

    private JournalFileSink(MinecraftServer server, Path dir, String runStamp) {
        this.server = server;
        this.dir = dir;
        this.runStamp = runStamp;
        this.handles = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<PersonId, BufferedWriter> eldest) {
                if (size() > MAX_OPEN_FILES) {
                    closeQuietly(eldest.getValue()); // reopened in append mode when it next writes
                    return true;
                }
                return false;
            }
        };
        this.writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "autarkia-journal-writer");
            thread.setDaemon(true);
            return thread;
        });
        this.writer.scheduleWithFixedDelay(this::flush, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
    }

    /** Create a sink for {@code server} and subscribe it to {@code journal}. Called once per boot. */
    static JournalFileSink attach(MinecraftServer server, JournalService journal) {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("autarkia");
        String stamp = LocalDateTime.now().format(STAMP); // real wall-clock — mod code, not a workflow
        JournalFileSink sink = new JournalFileSink(server, dir, stamp);
        journal.subscribe(sink::onEntry);
        return sink;
    }

    /** Server thread: pin the name (once) and queue the entry. Never blocks on I/O. */
    private void onEntry(PersonId id, Entry entry) {
        names.computeIfAbsent(id, this::resolveName);
        queue.offer(new Pending(id, entry));
    }

    private String resolveName(PersonId id) {
        return sanitize(PersonDirectory.get(server).nameOf(id).orElse("unknown"));
    }

    // --- writer thread ---------------------------------------------------------------------------

    /** Drain everything queued since the last cadence, grouped by person, appended and flushed. */
    private void flush() {
        List<Pending> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (batch.isEmpty()) {
            return;
        }
        Map<PersonId, List<String>> byPerson = new LinkedHashMap<>();
        for (Pending pending : batch) {
            byPerson.computeIfAbsent(pending.id(), key -> new ArrayList<>()).add(render(pending.entry()));
        }
        for (Map.Entry<PersonId, List<String>> person : byPerson.entrySet()) {
            try {
                BufferedWriter out = handleFor(person.getKey());
                for (String line : person.getValue()) {
                    out.write(line);
                    out.newLine();
                }
                out.flush();
            } catch (IOException e) {
                AutarkiaMod.LOGGER.warn("journal: failed writing log for {}", person.getKey(), e);
            }
        }
    }

    /** The open writer for {@code id}, opening (and header-stamping) its file on first use. */
    private BufferedWriter handleFor(PersonId id) throws IOException {
        BufferedWriter existing = handles.get(id);
        if (existing != null) {
            return existing;
        }
        Files.createDirectories(dir);
        String name = names.getOrDefault(id, "unknown");
        Path file = dir.resolve("person-" + id.value() + "-" + name + "-" + runStamp + ".log");
        boolean fresh = !Files.exists(file);
        BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (fresh) {
            out.write("# " + id.value() + "  " + name + "  — run " + runStamp);
            out.newLine();
            out.flush();
        }
        handles.put(id, out);
        return out;
    }

    /** One file line: {@code [<tick>] <category> - <event> - <detail>} (name/uuid are in the filename). */
    private static String render(Entry entry) {
        String base = "[" + entry.tick() + "] " + entry.category().name().toLowerCase(Locale.ROOT)
                + " - " + entry.event();
        return entry.detail().isEmpty() ? base : base + " - " + entry.detail();
    }

    // --- lifecycle -------------------------------------------------------------------------------

    /**
     * Stop the writer, drain whatever is left on this thread, and close every file — the clean
     * shutdown that loses nothing. Called from {@code SERVER_STOPPING} (server thread); after
     * {@link java.util.concurrent.ExecutorService#awaitTermination awaitTermination} the writer
     * thread is done, so the final {@link #flush()} and the handle close-out race nothing.
     */
    void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush(); // final drain of anything queued after the writer's last cycle
        for (BufferedWriter out : handles.values()) {
            closeQuietly(out);
        }
        handles.clear();
    }

    private static void closeQuietly(BufferedWriter out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // closing on shutdown/eviction — nothing useful to do with a failure here
        }
    }

    /** Make a display name safe as one filename segment. */
    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
