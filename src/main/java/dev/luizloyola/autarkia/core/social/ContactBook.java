package dev.luizloyola.autarkia.core.social;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Who knows whom — the world's contact books, one per knower, keyed by {@link PersonId}.
 *
 * <p><b>A name is not a property of a body</b>, it is something you were told (decision: Luiz):
 * perception says what a body is doing and looks like, this says whether the observer may name it.
 * External identity (skin, gender, model) is public; the name is earned.
 *
 * <p><b>Players are ordinary knowers</b>: a player's {@link PersonId} is minted from their account
 * UUID, so their book is another row here and a gossip rung can write {@code learn(charlie, alice)}
 * without caring who is human.
 *
 * <p><b>Knowing is one-directional.</b> {@link #learn} records one side; {@link #introduce} is two
 * independent facts at one moment. Overhearing is the asymmetric case: Charlie learns Alice without
 * Alice ever learning Charlie.
 *
 * <p>Pure core, single-threaded by contract (the server thread); persistence is the {@code mod}
 * layer's job.
 */
public final class ContactBook {

    /** Insertion-ordered per knower, so a save round-trips in a stable, readable order. */
    private final Map<PersonId, Set<PersonId>> books = new HashMap<>();

    /**
     * Whether {@code knower} can put a name to {@code whom}. Everyone knows themselves, so a
     * Person's own journal and commands never have to special-case it.
     */
    public boolean knows(PersonId knower, PersonId whom) {
        if (knower.equals(whom)) {
            return true;
        }
        Set<PersonId> book = books.get(knower);
        return book != null && book.contains(whom);
    }

    /**
     * Returns {@code true} when this was genuinely new — the caller's cue to sync a client or
     * narrate the moment; a repeat introduction is a no-op.
     */
    public boolean learn(PersonId knower, PersonId whom) {
        if (knower.equals(whom)) {
            return false; // already true by construction, and never worth storing
        }
        return books.computeIfAbsent(knower, key -> new LinkedHashSet<>()).add(whom);
    }

    /** Both sides learn each other. */
    public boolean introduce(PersonId one, PersonId other) {
        boolean learned = learn(one, other);
        return learn(other, one) || learned;
    }

    /** Returns whether anything was actually dropped. */
    public boolean forget(PersonId knower, PersonId whom) {
        Set<PersonId> book = books.get(knower);
        if (book == null || !book.remove(whom)) {
            return false;
        }
        if (book.isEmpty()) {
            books.remove(knower); // an empty book is indistinguishable from no book
        }
        return true;
    }

    /** Returns whether they knew anyone at all. */
    public boolean clear(PersonId knower) {
        return books.remove(knower) != null;
    }

    /** Everyone {@code knower} can name, in the order they were met. Never includes themselves. */
    public Set<PersonId> contactsOf(PersonId knower) {
        Set<PersonId> book = books.get(knower);
        return book == null ? Set.of() : Collections.unmodifiableSet(book);
    }

    /** Everyone who knows anyone — for saving and for dev listings. */
    public Set<PersonId> knowers() {
        return Collections.unmodifiableSet(books.keySet());
    }

    public int size(PersonId knower) {
        return contactsOf(knower).size();
    }
}
