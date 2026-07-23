package dev.luizloyola.autarkia.mod.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.autarkia.compat.inv.ItemStacks;
import dev.luizloyola.autarkia.core.brain.task.GoTo;
import dev.luizloyola.autarkia.core.brain.task.SatisfyHunger;
import dev.luizloyola.autarkia.core.inv.ArmorType;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.autarkia.core.person.Needs;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Developer/admin commands for inspecting Autarkia state.
 *
 * <p>{@code whois} prints a Person's identity (id + name), read straight from the server-side
 * {@link PersonDirectory}: the name is never synced to clients.
 *
 * <p>{@code nav} drives the legs directly (locomotion debug) where {@code brain} runs the same work
 * through the task machinery the arbiter feeds; {@code brain auto on | off} flips autonomy — ON by
 * default, and a manual {@code goto}/{@code eat} flips it OFF the moment it runs.
 *
 * <p>{@code person spawn [<pos>] [name]} registers an identity in the {@link PersonDirectory} and
 * links it to the entity before it enters the world; a plain {@code /summon autarkia:person}
 * instead mints one on the entity's first server tick. Position mirrors {@code /summon}.
 * {@code person spawn nobrain} is that same path with autonomy off: an inert body for exercising
 * one feature at a time.
 *
 * <p>Every person-scoped subcommand resolves through {@link #resolve}: the source's pin, else the
 * nearest. {@code select} pins by name or short-id, or by what a player is looking at; {@code list}
 * enumerates the loaded Persons, since names are not unique. Pins live in {@link PersonSelection} —
 * in memory, per source, gone on restart.
 */
public final class AutarkiaCommands {
    private AutarkiaCommands() {}

    private static final double NEAREST_RADIUS = 32.0;

    /** The equipment slots a Person actually has (a player's set): both hands + the four armor pieces. */
    private static final java.util.Set<EquipmentSlot> PERSON_EQUIP_SLOTS = java.util.EnumSet.of(
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("autarkia")
                        // Pin the Person that this source's later commands target. "clear"/"show" are
                        // literals, so they win over a Person literally named clear/show — pin those by id.
                        .then(Commands.literal("select")
                                .executes(ctx -> selectHere(ctx.getSource()))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> selectClear(ctx.getSource())))
                                .then(Commands.literal("show")
                                        .executes(ctx -> selectShow(ctx.getSource())))
                                .then(Commands.argument("person", StringArgumentType.string())
                                        .suggests(PERSON_SUGGESTIONS)
                                        .executes(ctx -> selectByToken(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "person")))))
                        .then(Commands.literal("list")
                                .executes(ctx -> listPersons(ctx.getSource())))
                        .then(Commands.literal("whois")
                                .executes(ctx -> whoisNearest(ctx.getSource()))
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> whoisTargets(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "targets")))))
                        .then(Commands.literal("nav")
                                .then(Commands.literal("goto")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> navGoto(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                                .then(Commands.literal("stop")
                                        .executes(ctx -> navStop(ctx.getSource())))
                                .then(Commands.literal("status")
                                        .executes(ctx -> navStatus(ctx.getSource()))))
                        // nav (above) drives the legs directly — locomotion debug; brain runs
                        // tasks through the executor, the machinery the arbiter will feed.
                        .then(Commands.literal("brain")
                                .then(Commands.literal("goto")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> brainGoto(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                                .then(Commands.literal("eat")
                                        .executes(ctx -> brainEat(ctx.getSource())))
                                .then(Commands.literal("status")
                                        .executes(ctx -> brainStatus(ctx.getSource())))
                                .then(Commands.literal("cancel")
                                        .executes(ctx -> brainCancel(ctx.getSource())))
                                // The autonomy switch — spawns start ON.
                                .then(Commands.literal("auto")
                                        .then(Commands.literal("on")
                                                .executes(ctx -> brainAuto(ctx.getSource(), true)))
                                        .then(Commands.literal("off")
                                                .executes(ctx -> brainAuto(ctx.getSource(), false)))))
                        .then(Commands.literal("inv")
                                .then(Commands.literal("list")
                                        .executes(ctx -> invList(ctx.getSource())))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> invClear(ctx.getSource())))
                                .then(Commands.literal("give")
                                        .then(Commands.argument("item", ItemArgument.item(registryAccess))
                                                .executes(ctx -> invGive(ctx.getSource(),
                                                        ItemArgument.getItem(ctx, "item"), 1))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> invGive(ctx.getSource(),
                                                                ItemArgument.getItem(ctx, "item"),
                                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(Commands.literal("equip")
                                        .then(Commands.argument("item", ItemArgument.item(registryAccess))
                                                .executes(ctx -> invEquip(ctx.getSource(),
                                                        ItemArgument.getItem(ctx, "item"))))))
                        // "person", not "brain": these are body readouts (vitals live with the
                        // entity); the brain group above holds the decision machinery.
                        .then(Commands.literal("person")
                                // "spawn" makes an autonomous Person; "nobrain" makes one with
                                // autonomy off, for testing one feature at a time. Both take the same
                                // [<pos>] [name] leaves (see spawnLeaves) and differ only in the brain
                                // flag; "nobrain" is a literal, so quote it to use it as a name.
                                .then(spawnLeaves(Commands.literal("spawn"), true)
                                        .then(spawnLeaves(Commands.literal("nobrain"), false)))
                                .then(Commands.literal("needs")
                                        .executes(ctx -> personNeeds(ctx.getSource())))
                                .then(Commands.literal("setfood")
                                        .then(Commands.argument("food",
                                                        IntegerArgumentType.integer(0, Needs.MAX_FOOD))
                                                .executes(ctx -> personSetFood(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "food"), 0.0F))
                                                .then(Commands.argument("saturation",
                                                                FloatArgumentType.floatArg(0.0F, Needs.MAX_FOOD))
                                                        .executes(ctx -> personSetFood(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "food"),
                                                                FloatArgumentType.getFloat(ctx, "saturation")))))))));
    }

    private static int navGoto(CommandSourceStack source, BlockPos pos) {
        Person person = resolve(source);
        if (person == null) return 0;
        person.navigateTo(Vec3.atBottomCenterOf(pos));
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " -> "
                + pos.toShortString()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int navStop(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        person.navigator().stop();
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " stopped.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int navStatus(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.navigator().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Runs a {@link GoTo} task on the nearest Person through the brain's executor — same walk
     *  as {@link #navGoto}, but through the task machinery, so the whole pipeline is exercised. */
    private static int brainGoto(CommandSourceStack source, BlockPos pos) {
        Person person = resolve(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new GoTo(pos.getX(), pos.getY(), pos.getZ()));
        String suffix = autoDisabledSuffix(autoDisabled);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Runs {@link SatisfyHunger} on the nearest Person — the first COMPOUND task, and the
     *  machinery the Eat instinct also drives (autonomously) via the arbiter. */
    private static int brainEat(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new SatisfyHunger());
        String suffix = autoDisabledSuffix(autoDisabled);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int brainStatus(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int brainCancel(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        person.brain().cancel();
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " task cancelled; "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Flips the nearest Person's autonomy switch and echoes the new describe() line (now
     *  reporting auto|manual up front). */
    private static int brainAuto(CommandSourceStack source, boolean auto) {
        Person person = resolve(source);
        if (person == null) return 0;
        person.brain().setAuto(auto);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** The note appended to a manual {@code brain goto}/{@code brain eat} reply when that very
     *  call is what took the wheel from the arbiter. */
    private static String autoDisabledSuffix(boolean autoDisabled) {
        return autoDisabled ? " (auto disabled — re-enable with /autarkia brain auto on)" : "";
    }

    /** Prints every non-empty slot of the nearest Person's inventory (storage + equipment). */
    private static int invList(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        List<Inventory.Entry> occupied = person.inventory().occupied();
        if (occupied.isEmpty()) {
            source.sendSuccess(() -> Component.literal(person.getName().getString() + " carries nothing.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " carries:")
                .withStyle(ChatFormatting.AQUA), false);
        for (Inventory.Entry entry : occupied) {
            String line = "  " + slotLabel(entry.slot()) + "  " + entry.stack().id() + " x" + entry.stack().count();
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return occupied.size();
    }

    /** Adds {@code count} of the given item to the nearest Person, reporting anything that didn't fit. */
    private static int invGive(CommandSourceStack source, ItemInput input, int count)
            throws CommandSyntaxException {
        Person person = resolve(source);
        if (person == null) return 0;
        // A count-1 template never trips the item argument's stack-size guard; the real count is set
        // in the core layer, which splits it across slots at the item's own cap. Components (from any
        // {…} the command carried) ride along via toCore.
        dev.luizloyola.autarkia.core.inv.ItemStack template =
                ItemStacks.templateOf(input, source.registryAccess());
        dev.luizloyola.autarkia.core.inv.ItemStack remainder = person.inventory().add(template.withCount(count));
        int placed = count - remainder.count();
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " +" + placed + " "
                + template.id() + (remainder.isEmpty() ? "" : "  (" + remainder.count() + " didn't fit)"))
                .withStyle(ChatFormatting.AQUA), false);
        return placed;
    }

    /**
     * Moves one of the given item from storage into its natural equipment slot, returning any
     * piece it displaces to storage. The brain-less driver for wearing gear — like
     * {@code nav goto} is for walking.
     */
    private static int invEquip(CommandSourceStack source, ItemInput input) throws CommandSyntaxException {
        Person person = resolve(source);
        if (person == null) return 0;
        // The template resolves the kind + its natural slot only; the piece actually equipped is
        // pulled from storage below, so its own components (enchants, damage, …) are preserved.
        dev.luizloyola.autarkia.core.inv.ItemStack want =
                ItemStacks.templateOf(input, source.registryAccess());
        EquipmentSlot slot = ItemStacks.equipmentSlotOf(want);
        if (slot == null) {
            source.sendFailure(Component.literal(want.id() + " is not equippable."));
            return 0;
        }
        if (!PERSON_EQUIP_SLOTS.contains(slot)) { // e.g. BODY/SADDLE — no such slot on a Person
            source.sendFailure(Component.literal(want.id() + " can't be worn by a Person (" + slot.getName() + ")."));
            return 0;
        }
        Inventory inv = person.inventory();
        dev.luizloyola.autarkia.core.inv.ItemStack piece = inv.takeOne(want.id());
        if (piece.isEmpty()) {
            source.sendFailure(Component.literal(person.getName().getString() + " has no " + want.id() + " to equip."));
            return 0;
        }
        dev.luizloyola.autarkia.core.inv.ItemStack displaced = placeEquipment(inv, slot, piece);
        if (!displaced.isEmpty()) inv.add(displaced); // whatever was worn there goes back to storage
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " equipped "
                + want.id() + " (" + slot.getName() + ")").withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Places {@code stack} in the core inventory slot for {@code slot}, returning what was there. */
    private static dev.luizloyola.autarkia.core.inv.ItemStack placeEquipment(
            Inventory inv, EquipmentSlot slot, dev.luizloyola.autarkia.core.inv.ItemStack stack) {
        switch (slot) {
            case OFFHAND -> {
                dev.luizloyola.autarkia.core.inv.ItemStack prev = inv.offhand();
                inv.setOffhand(stack);
                return prev;
            }
            case MAINHAND -> {
                dev.luizloyola.autarkia.core.inv.ItemStack prev = inv.mainHand();
                inv.set(inv.selectedSlot(), stack);
                return prev;
            }
            default -> { // the four armor slots — HEAD/CHEST/LEGS/FEET names match ArmorType
                ArmorType type = ArmorType.valueOf(slot.name());
                dev.luizloyola.autarkia.core.inv.ItemStack prev = inv.armor(type);
                inv.setArmor(type, stack);
                return prev;
            }
        }
    }

    private static int invClear(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        person.inventory().clear();
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " inventory cleared.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /**
     * Attaches the optional {@code [<pos>] [name]} leaves to a spawn literal, so {@code spawn} and
     * its {@code nobrain} child share one argument shape and differ only in the brain flag they hand
     * {@link #personSpawn}.
     *
     * <p>Position mirrors {@code /summon}'s {@code <pos>}. The name is a NON-greedy string on
     * purpose: against coords like {@code 10 -59 5} a one-word name consumes only {@code 10} and
     * leaves {@code -59 5} unparsed, so that branch loses to the Vec3 one, while a bare {@code Bob}
     * falls to the name. Multi-word names must be quoted; a greedy name would swallow the line.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> spawnLeaves(
            LiteralArgumentBuilder<CommandSourceStack> node, boolean autonomous) {
        return node
                .executes(ctx -> personSpawn(ctx.getSource(), null, null, autonomous))
                .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(ctx -> personSpawn(ctx.getSource(),
                                Vec3Argument.getVec3(ctx, "pos"), null, autonomous))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> personSpawn(ctx.getSource(),
                                        Vec3Argument.getVec3(ctx, "pos"),
                                        StringArgumentType.getString(ctx, "name"), autonomous))))
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> personSpawn(ctx.getSource(), null,
                                StringArgumentType.getString(ctx, "name"), autonomous)));
    }

    /**
     * Spawns a new Person at {@code pos} (or the source's position when {@code null}), facing south
     * like {@code /summon}. Directory-first: the identity — {@code name} if given, else generated —
     * is registered and linked to the entity before it enters the world, where a plain
     * {@code /summon} mints one a tick later in {@link Person#tick()}. The entity is created before
     * the directory is touched, so a null entity leaves no orphan entry.
     *
     * <p>{@code autonomous} is the arbiter switch every Person spawns with ON;
     * {@code person spawn nobrain} passes {@code false} before the first tick — an inert body,
     * still drivable via {@code /autarkia brain}.
     */
    private static int personSpawn(CommandSourceStack source, @Nullable Vec3 pos, @Nullable String name,
                                   boolean autonomous) {
        String trimmed = name == null ? null : name.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            source.sendFailure(Component.literal("Name must not be blank."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Person person = ModEntities.PERSON.create(level, EntitySpawnReason.COMMAND);
        if (person == null) {
            source.sendFailure(Component.literal("Could not create the Person entity."));
            return 0;
        }
        PersonDirectory directory = PersonDirectory.get(source.getServer());
        PersonIdentity identity = trimmed == null ? directory.createPerson() : directory.createPerson(trimmed);
        Vec3 spawnPos = pos != null ? pos : source.getPosition();
        // Yaw 0 = facing south, matching /summon (which keeps the entity's own default rotation);
        // pitch pinned flat since a Person stands upright (pitch is render-only head tilt, see face()).
        person.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, 0.0F, 0.0F);
        person.assignPerson(identity.id());
        // "no brain": drop the arbiter into manual mode before the entity's first serverAiStep, so
        // it spawns inert.
        if (!autonomous) {
            person.brain().setAuto(false);
        }
        if (!level.addFreshEntity(person)) {
            source.sendFailure(Component.literal("Could not add the Person to the world."));
            return 0;
        }
        Appearance appearance = identity.appearance();
        String where = String.format(Locale.ROOT, "%.1f %.1f %.1f", spawnPos.x, spawnPos.y, spawnPos.z);
        String brainNote = autonomous ? "" : " — brain off (/autarkia brain auto on to enable)";
        source.sendSuccess(() -> Component.literal("Spawned ")
                .append(Component.literal(identity.name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" (" + appearance.gender() + ") at " + where + brainNote)
                        .withStyle(ChatFormatting.GRAY)), true);
        return 1;
    }

    /** Prints the nearest Person's need levels — the {@code needs().describe()} one-liner. */
    private static int personNeeds(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.needs().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /**
     * Sets the nearest Person's food level (0..20) and saturation (0.0 when omitted) and echoes
     * the readout — the dev knob for exercising starvation and regen without waiting out the burn.
     * Food is set before saturation, which clamps against it; exhaustion is zeroed so behaviour
     * afterwards is deterministic.
     */
    private static int personSetFood(CommandSourceStack source, int food, float saturation) {
        Person person = resolve(source);
        if (person == null) return 0;
        Needs needs = person.needs();
        needs.setFoodLevel(food);
        needs.setSaturation(saturation);
        needs.setExhaustion(0.0F);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + needs.describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Human-readable region label for a raw inventory slot index. */
    private static String slotLabel(int slot) {
        if (slot < Inventory.MAIN_START) return "hotbar[" + slot + "]";
        if (slot < Inventory.ARMOR_START) return "main[" + (slot - Inventory.MAIN_START) + "]";
        if (slot < Inventory.OFFHAND_SLOT) {
            return ArmorType.values()[slot - Inventory.ARMOR_START].name().toLowerCase(Locale.ROOT);
        }
        return "offhand";
    }

    private static Person nearest(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        AABB box = AABB.ofSize(origin, NEAREST_RADIUS * 2, NEAREST_RADIUS * 2, NEAREST_RADIUS * 2);
        Person nearest = level.getEntitiesOfClass(Person.class, box).stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)))
                .orElse(null);
        if (nearest == null) {
            source.sendFailure(Component.literal(
                    "No Person within " + (int) NEAREST_RADIUS + " blocks."));
        }
        return nearest;
    }

    /**
     * The target for a person-scoped command: the source's pinned Person, else the nearest
     * ({@link #nearest}). A pin that no longer resolves to a loaded entity is a hard failure, never
     * a silent fall-through. Returns {@code null} having reported the reason.
     */
    private static @Nullable Person resolve(CommandSourceStack source) {
        Optional<PersonId> pin = PersonSelection.pinned(source);
        if (pin.isEmpty()) return nearest(source);
        PersonId id = pin.get();
        Person live = findLoaded(source.getServer(), id);
        if (live == null) {
            source.sendFailure(Component.literal("Selected " + label(source.getServer(), id)
                    + " isn't loaded — /autarkia select clear, or select someone else."));
        }
        return live;
    }

    /** Suggests every loaded Person's name (quoted when it has spaces) and short id, so {@code select}
     *  tab-completes to something that actually resolves. */
    private static final SuggestionProvider<CommandSourceStack> PERSON_SUGGESTIONS = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        PersonDirectory directory = PersonDirectory.get(server);
        Stream<String> tokens = loadedPersons(server).stream().flatMap(person -> {
            PersonId id = person.getPersonId();
            if (id == null) return Stream.empty();
            String shortId = shortId(id);
            return directory.nameOf(id)
                    .map(name -> Stream.of(name.contains(" ") ? '"' + name + '"' : name, shortId))
                    .orElseGet(() -> Stream.of(shortId));
        });
        return SharedSuggestionProvider.suggest(tokens, builder);
    };

    /** Pins the Person named (or short-id-prefixed) by {@code rawToken}. */
    private static int selectByToken(CommandSourceStack source, String rawToken) {
        String token = rawToken.trim();
        MinecraftServer server = source.getServer();
        PersonDirectory directory = PersonDirectory.get(server);
        Vec3 origin = source.getPosition();
        List<Person> loaded = loadedPersons(server);

        // An id (or short-id prefix) is an unambiguous handle, so it's tried first; only if nothing
        // matches by id do we fall back to a case-insensitive name match (names aren't unique).
        String lower = token.toLowerCase(Locale.ROOT);
        List<Person> matches = loaded.stream()
                .filter(p -> p.getPersonId() != null
                        && p.getPersonId().toString().toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
        if (matches.isEmpty()) {
            matches = loaded.stream()
                    .filter(p -> p.getPersonId() != null
                            && directory.nameOf(p.getPersonId()).map(n -> n.equalsIgnoreCase(token)).orElse(false))
                    .toList();
        }
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal(
                    "No loaded Person matches '" + token + "' — try /autarkia list."));
            return 0;
        }
        int count = matches.size();
        Person chosen = matches.stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)))
                .orElseThrow();
        PersonId id = chosen.getPersonId();
        PersonSelection.pin(source, id);
        source.sendSuccess(() -> Component.literal("Selected " + label(server, id)
                + (count > 1 ? " (nearest of " + count + " matches)" : "")).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** No-argument {@code select}: a player pins the Person under their crosshair; the console (or any
     *  non-player source) pins the nearest one. */
    private static int selectHere(CommandSourceStack source) {
        boolean fromPlayer = source.getEntity() instanceof ServerPlayer;
        Person target = source.getEntity() instanceof ServerPlayer player ? lookedAt(player) : nearest(source);
        if (target == null) {
            if (fromPlayer) {
                source.sendFailure(Component.literal("You're not looking at a Person (within "
                        + (int) NEAREST_RADIUS + " blocks)."));
            }
            // the console branch already reported via nearest()
            return 0;
        }
        PersonId id = target.getPersonId();
        if (id == null) {
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        PersonSelection.pin(source, id);
        source.sendSuccess(() -> Component.literal("Selected " + label(source.getServer(), id))
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int selectClear(CommandSourceStack source) {
        if (PersonSelection.clear(source)) {
            source.sendSuccess(() -> Component.literal("Selection cleared — commands use the nearest Person again.")
                    .withStyle(ChatFormatting.AQUA), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("No Person was selected.").withStyle(ChatFormatting.GRAY), false);
        return 0;
    }

    private static int selectShow(CommandSourceStack source) {
        Optional<PersonId> pin = PersonSelection.pinned(source);
        if (pin.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No selection — commands use the nearest Person.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        PersonId id = pin.get();
        boolean loaded = findLoaded(source.getServer(), id) != null;
        source.sendSuccess(() -> Component.literal("Selected: " + label(source.getServer(), id)
                + (loaded ? "" : " (not loaded)")).withStyle(loaded ? ChatFormatting.AQUA : ChatFormatting.GRAY), false);
        return loaded ? 1 : 0;
    }

    /** Lists the loaded Persons, nearest first: a {@code ✓} on the pinned one, then name, short id,
     *  dimension, and distance. This is how you find out what to {@code select}. */
    private static int listPersons(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        PersonDirectory directory = PersonDirectory.get(server);
        Vec3 origin = source.getPosition();
        List<Person> loaded = loadedPersons(server);
        if (loaded.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No Persons are loaded.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        Optional<PersonId> pin = PersonSelection.pinned(source);
        loaded.stream()
                .sorted((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)))
                .forEach(person -> {
                    PersonId id = person.getPersonId();
                    boolean isPinned = id != null && pin.map(id::equals).orElse(false);
                    String name = id == null ? "<spawning>" : directory.nameOf(id).orElse("<unknown>");
                    String dimension = person.level().dimension().identifier().getPath();
                    double distance = Math.sqrt(person.distanceToSqr(origin));
                    String line = String.format(Locale.ROOT, "%s%s  %s  %s  %.1fm",
                            isPinned ? "✓ " : "  ", name, id == null ? "-" : shortId(id), dimension, distance);
                    source.sendSuccess(() -> Component.literal(line)
                            .withStyle(isPinned ? ChatFormatting.AQUA : ChatFormatting.GRAY), false);
                });
        return loaded.size();
    }

    /** The Person a player's crosshair is on, within {@link #NEAREST_RADIUS}, or {@code null}. */
    private static @Nullable Person lookedAt(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 far = eye.add(player.getViewVector(1.0F).scale(NEAREST_RADIUS));
        AABB search = player.getBoundingBox().expandTowards(player.getViewVector(1.0F).scale(NEAREST_RADIUS)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, far, search, e -> e instanceof Person, NEAREST_RADIUS * NEAREST_RADIUS);
        return hit != null && hit.getEntity() instanceof Person person ? person : null;
    }

    /** Every live Person across every dimension. {@code getEntities} still hands back a Person killed
     *  moments ago (it lingers through its death animation before being swept), so {@code isAlive}
     *  is filtered on, or {@code list} and the resolver would act on a corpse. */
    private static List<Person> loadedPersons(MinecraftServer server) {
        List<Person> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            out.addAll(level.getEntities(ModEntities.PERSON, Person::isAlive));
        }
        return out;
    }

    /** The live Person with this id, searching every dimension, or {@code null} if none is loaded.
     *  A dead/dying Person (not yet swept) does not count — see {@link #loadedPersons}. */
    private static @Nullable Person findLoaded(MinecraftServer server, PersonId id) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Person person : level.getEntities(ModEntities.PERSON, p -> p.isAlive() && id.equals(p.getPersonId()))) {
                return person;
            }
        }
        return null;
    }

    /** The person's name if the directory knows it, else the short id — a stable label for messages. */
    private static String label(MinecraftServer server, PersonId id) {
        return PersonDirectory.get(server).nameOf(id).orElse(shortId(id));
    }

    /** The first 8 characters of an id — enough to eyeball and to prefix-match in {@code select}. */
    private static String shortId(PersonId id) {
        String text = id.toString();
        return text.substring(0, Math.min(8, text.length()));
    }

    private static int whoisNearest(CommandSourceStack source) {
        Person target = resolve(source);
        if (target == null) return 0;
        report(source, target);
        return 1;
    }

    private static int whoisTargets(CommandSourceStack source,
                                    java.util.Collection<? extends Entity> targets) {
        List<Person> persons = targets.stream().filter(e -> e instanceof Person).map(e -> (Person) e).toList();
        if (persons.isEmpty()) {
            source.sendFailure(Component.literal("No Person among the selected entities."));
            return 0;
        }
        persons.forEach(person -> report(source, person));
        return persons.size();
    }

    private static void report(CommandSourceStack source, Person person) {
        PersonId id = person.getPersonId();
        if (id == null) {
            source.sendSuccess(() -> Component.literal("Person not yet identified (spawning).")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        PersonIdentity identity = PersonDirectory.get(source.getServer()).find(id).orElse(null);
        if (identity == null) {
            source.sendSuccess(() -> Component.literal(id + "  <unknown>").withStyle(ChatFormatting.GRAY), false);
            return;
        }
        Appearance appearance = identity.appearance();
        // Full tier (name) + external tier (gender, skin) + the stable id.
        ChatFormatting genderColor = appearance.gender().choose(ChatFormatting.BLUE, ChatFormatting.LIGHT_PURPLE);
        MutableComponent line = Component.literal(identity.name()).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" " + appearance.gender()).withStyle(genderColor))
                .append(Component.literal(" " + appearance.model()).withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal("  " + appearance.skin()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  " + id).withStyle(ChatFormatting.DARK_GRAY));
        source.sendSuccess(() -> line, false);
    }
}
