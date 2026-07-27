package dev.luizloyola.autarkia.core.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contact book's rules: knowing is EARNED (an unmet person is nameless), one-directional
 * (Charlie can overhear Alice's name without Alice ever learning his), and free only for
 * yourself. Everything else (introductions, forgetting, ordering) falls out of those three.
 */
class ContactBookTest {

    private final ContactBook book = new ContactBook();
    private final PersonId alice = PersonId.random();
    private final PersonId bob = PersonId.random();
    private final PersonId charlie = PersonId.random();

    @Test
    void nobodyIsKnownUntilTheyAreMet() {
        assertFalse(book.knows(alice, bob));
        assertTrue(book.contactsOf(alice).isEmpty());
        assertEquals(0, book.size(alice));
    }

    @Test
    void everyoneKnowsThemselves() {
        assertTrue(book.knows(alice, alice));
        // ...and it is never STORED: an empty book must stay empty, or every listing is wrong.
        assertFalse(book.learn(alice, alice));
        assertTrue(book.contactsOf(alice).isEmpty());
    }

    @Test
    void learningIsOneDirectional() {
        assertTrue(book.learn(charlie, alice)); // overheard her name in someone else's chat
        assertTrue(book.knows(charlie, alice));
        assertFalse(book.knows(alice, charlie));
    }

    @Test
    void learningReportsOnlyTheFirstTime() {
        assertTrue(book.learn(alice, bob));
        assertFalse(book.learn(alice, bob)); // a repeat introduction syncs and narrates nothing
        assertEquals(1, book.size(alice));
    }

    @Test
    void introducingWritesBothSides() {
        assertTrue(book.introduce(alice, bob));
        assertTrue(book.knows(alice, bob));
        assertTrue(book.knows(bob, alice));
        assertFalse(book.introduce(alice, bob)); // nothing new either way
    }

    @Test
    void introducingReportsWhenOnlyOneSideIsNew() {
        book.learn(alice, bob);
        assertTrue(book.introduce(alice, bob)); // bob still had to learn alice
    }

    @Test
    void booksAreIndependent() {
        book.learn(alice, bob);
        book.learn(charlie, bob);
        assertTrue(book.forget(alice, bob));
        assertFalse(book.knows(alice, bob));
        assertTrue(book.knows(charlie, bob)); // charlie never met alice's memory
    }

    @Test
    void forgettingWhatWasNeverKnownChangesNothing() {
        assertFalse(book.forget(alice, bob));
        assertFalse(book.clear(alice));
    }

    @Test
    void clearingEmptiesOneBookOnly() {
        book.introduce(alice, bob);
        book.learn(charlie, alice);
        assertTrue(book.clear(alice));
        assertFalse(book.knows(alice, bob));
        assertTrue(book.knows(bob, alice));
        assertTrue(book.knows(charlie, alice));
    }

    @Test
    void contactsKeepTheOrderTheyWereMetIn() {
        book.learn(alice, charlie);
        book.learn(alice, bob);
        assertEquals(List.of(charlie, bob), List.copyOf(book.contactsOf(alice)));
    }

    @Test
    void knowersListsOnlyPeopleWhoKnowSomeone() {
        book.learn(alice, bob);
        assertEquals(List.of(alice), List.copyOf(book.knowers()));
        book.forget(alice, bob);
        assertTrue(book.knowers().isEmpty()); // an emptied book leaves no ghost row behind
    }
}
