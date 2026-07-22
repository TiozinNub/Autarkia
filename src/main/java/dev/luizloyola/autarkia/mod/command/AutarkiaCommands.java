package dev.luizloyola.autarkia.mod.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.luizloyola.autarkia.compat.inv.ItemStacks;
import dev.luizloyola.autarkia.core.inv.ArmorType;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.autarkia.core.person.Needs;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Developer/admin commands for inspecting Autarkia state.
 *
 * <p>{@code whois} prints a Person's identity (id + name), read straight from the server-side
 * {@link PersonDirectory}: the name is never synced to clients.
 *
 * <p>{@code nav goto <pos> | stop | status} drives a Person's navigator, so locomotion is
 * exercisable from a headless dev server and by command blocks.
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
                        // entity).
                        .then(Commands.literal("person")
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
        Person person = nearest(source);
        if (person == null) return 0;
        person.navigateTo(Vec3.atBottomCenterOf(pos));
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " -> "
                + pos.toShortString()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int navStop(CommandSourceStack source) {
        Person person = nearest(source);
        if (person == null) return 0;
        person.navigator().stop();
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " stopped.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int navStatus(CommandSourceStack source) {
        Person person = nearest(source);
        if (person == null) return 0;
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.navigator().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Prints every non-empty slot of the nearest Person's inventory (storage + equipment). */
    private static int invList(CommandSourceStack source) {
        Person person = nearest(source);
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
        Person person = nearest(source);
        if (person == null) return 0;
        // A count-1 template never trips the item argument's stack-size guard; the real count is set
        // in the core layer, which splits it across slots at the item's own cap. Components (from any
        // {…} the command carried) ride along via toCore.
        dev.luizloyola.autarkia.core.inv.ItemStack template =
                ItemStacks.toCore(input.createItemStack(1), source.registryAccess());
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
        Person person = nearest(source);
        if (person == null) return 0;
        // The template resolves the kind + its natural slot only; the piece actually equipped is
        // pulled from storage below, so its own components (enchants, damage, …) are preserved.
        dev.luizloyola.autarkia.core.inv.ItemStack want =
                ItemStacks.toCore(input.createItemStack(1), source.registryAccess());
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
        Person person = nearest(source);
        if (person == null) return 0;
        person.inventory().clear();
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " inventory cleared.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Prints the nearest Person's need levels — the {@code needs().describe()} one-liner. */
    private static int personNeeds(CommandSourceStack source) {
        Person person = nearest(source);
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
        Person person = nearest(source);
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

    private static int whoisNearest(CommandSourceStack source) {
        Person nearest = nearest(source);
        if (nearest == null) return 0;
        report(source, nearest);
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
