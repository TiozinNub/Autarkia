package dev.luizloyola.autarkia.mod.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.autarkia.compat.SavedDatas;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.core.social.ContactBook;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The world-scoped, persisted home of every contact book — the {@code PersonDirectory} pattern
 * applied to acquaintance ({@code <world>/data/autarkia_contacts.dat}). Player books live here
 * rather than in player data because a player's {@link PersonId} is minted from their account
 * UUID: one store, one rule, and gossip about a logged-out player needs no special case.
 *
 * <p>The pure {@link ContactBook} holds the logic; this owns persistence and the dirty flag, so
 * every mutation goes through here rather than handing the book out.
 */
public final class ContactData extends SavedData {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("autarkia", "contacts");

    /** One knower's row: {@code {who, knows:[…]}}. */
    private record Row(UUID who, List<UUID> knows) {
    }

    private static final Codec<Row> ROW_CODEC = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("who").forGetter(Row::who),
            UUIDUtil.CODEC.listOf().fieldOf("knows").forGetter(Row::knows)
    ).apply(row, Row::new));

    private static final Codec<ContactData> CODEC = RecordCodecBuilder.create(data -> data.group(
            ROW_CODEC.listOf().fieldOf("books").forGetter(ContactData::rows)
    ).apply(data, ContactData::fromRows));

    public static final SavedDataType<ContactData> TYPE =
            SavedDatas.type(ID, ContactData::new, CODEC, DataFixTypes.LEVEL);

    private final ContactBook book;

    /** Constructs an empty store (the {@link SavedDataType} supplier for a fresh save). */
    public ContactData() {
        this(new ContactBook());
    }

    private ContactData(ContactBook book) {
        this.book = book;
    }

    /** Resolves the single, server-global store (kept on the overworld's data storage). */
    public static ContactData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** @see ContactBook#knows */
    public boolean knows(PersonId knower, PersonId whom) {
        return book.knows(knower, whom);
    }

    /** @see ContactBook#contactsOf */
    public Set<PersonId> contactsOf(PersonId knower) {
        return book.contactsOf(knower);
    }

    /** @see ContactBook#knowers */
    public Set<PersonId> knowers() {
        return book.knowers();
    }

    /** @see ContactBook#learn — marks dirty only when something was genuinely new. */
    public boolean learn(PersonId knower, PersonId whom) {
        return dirtyIf(book.learn(knower, whom));
    }

    /** @see ContactBook#introduce */
    public boolean introduce(PersonId one, PersonId other) {
        return dirtyIf(book.introduce(one, other));
    }

    /** @see ContactBook#forget */
    public boolean forget(PersonId knower, PersonId whom) {
        return dirtyIf(book.forget(knower, whom));
    }

    /** @see ContactBook#clear */
    public boolean clear(PersonId knower) {
        return dirtyIf(book.clear(knower));
    }

    private boolean dirtyIf(boolean changed) {
        if (changed) {
            setDirty();
        }
        return changed;
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (PersonId knower : book.knowers()) {
            List<UUID> knows = new ArrayList<>();
            for (PersonId known : book.contactsOf(knower)) {
                knows.add(known.value());
            }
            rows.add(new Row(knower.value(), knows));
        }
        return rows;
    }

    private static ContactData fromRows(List<Row> rows) {
        ContactBook book = new ContactBook();
        for (Row row : rows) {
            for (UUID known : row.knows()) {
                book.learn(PersonId.of(row.who()), PersonId.of(known));
            }
        }
        return new ContactData(book);
    }
}
