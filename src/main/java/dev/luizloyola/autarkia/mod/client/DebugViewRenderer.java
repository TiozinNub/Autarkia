package dev.luizloyola.autarkia.mod.client;

import dev.luizloyola.autarkia.compat.client.debug.GizmoFrame;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.sense.Peer;
import dev.luizloyola.autarkia.core.nav.MoveType;
import dev.luizloyola.autarkia.mod.debug.DebugLayer;
import dev.luizloyola.autarkia.mod.net.DebugViewPayload;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The in-world debug view, client half: draws the latest {@link DebugViewClient} snapshot through
 * the same {@code net.minecraft.gizmos} API vanilla's hitbox view uses, so it depth-sorts like the
 * game's own.
 *
 * <p>Hooked through {@link GizmoFrame}: the gizmo collector is a thread-local the level renderer
 * installs, and outside its window every static {@code Gizmos} call is silently discarded. Which
 * Fabric event that is differs by version, so the hook lives in {@code compat} and none of the
 * drawing does. Immediate mode — a dropped payload redraws the previous truth.
 *
 * <p><b>Snapshot facts and live facts are mixed.</b> Waypoints, beliefs and peer
 * positions come off the wire; their own position and head yaw are read from the LOCAL entity every
 * frame, so the first leg stays glued to their feet and the cone turns as they look, between
 * four-tick snapshots.
 */
@Environment(EnvType.CLIENT)
public final class DebugViewRenderer {
    private DebugViewRenderer() {}

    // Path colours by move type: a leap and a stroll are the same cells and different intentions.
    private static final int WALK_COLOR = 0xFFB0C4FF;
    private static final int JUMP_COLOR = 0xFFFFE066;
    private static final int DROP_COLOR = 0xFFFF9E3D;
    private static final int LEAP_COLOR = 0xFFFF4D4D;
    private static final int SWIM_COLOR = 0xFF4DD2FF;
    private static final int GOAL_COLOR = 0xFF57F287;

    private static final int TREE_COLOR = 0xFF3FBF5F;
    private static final int WATER_COLOR = 0xFF3F8FFF;
    private static final int GHOST_COLOR = 0xFF9A9A9A;

    private static final int SEEN_COLOR = 0xFF57F287;
    private static final int HEARD_COLOR = 0xFFFFD24D;
    private static final int REMEMBERED_COLOR = 0xFF8A8A8A;
    private static final int CONE_COLOR = 0x66FFFFFF;

    private static final int NAV_TEXT_COLOR = 0xFFB0C4FF;
    private static final int BRAIN_TEXT_COLOR = 0xFFFFFFFF;

    /** Line widths: the leg being walked now is drawn heavier than the rest of the plan. */
    private static final float PATH_WIDTH = 2.5F;
    private static final float CURRENT_LEG_WIDTH = 5.0F;
    private static final float THIN = 1.5F;

    /** Waypoint lines float just above the floor of their cell. */
    private static final double PATH_Y = 0.15;

    /** Text sizes, matching vanilla's own debug renderers (title line vs detail lines). */
    private static final float TITLE_SCALE = 0.48F;
    private static final float DETAIL_SCALE = 0.32F;

    /** Vertical gap between stacked text lines — vanilla's spacing, kept so the stack reads the same. */
    private static final double TEXT_LINE_STEP = 0.25;
    /** How far above their head the stack starts: enough to clear the always-visible name tag. */
    private static final double NAME_TAG_CLEARANCE = 0.9;
    /** Vanilla's left-alignment nudge for stacked debug text. */
    private static final float TEXT_LEFT_ALIGN = 0.5F;
    /** Stand-in height for a peer with no loaded body — a player's, since every peer is one. */
    private static final double ASSUMED_BODY_HEIGHT = 1.8;

    /** Segments in the drawn cone arc — enough to read as a curve at any sane radius. */
    private static final int CONE_SEGMENTS = 24;

    public static void install() {
        GizmoFrame.onFrame(DebugViewRenderer::draw);
    }

    private static void draw() {
        DebugViewPayload view = DebugViewClient.get();
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (view == null || view.isEmpty() || level == null) {
            return;
        }
        // The Person may be out of client render distance while their path and beliefs are still
        // perfectly drawable — those are world-anchored. Only the head-mounted text and the cone
        // need a body, and they check for one.
        Entity person = level.getEntity(view.entityId());
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        // One shared counter so PATH's status line and BRAIN's stack pile up over their head in
        // registration order instead of overprinting each other.
        int[] line = {0};

        if (DebugLayer.PATH.in(view.layers())) {
            drawPath(view, person, partialTick);
            if (person != null && !view.nav().isBlank()) {
                overhead(person, partialTick, line[0]++, view.nav(), NAV_TEXT_COLOR, TITLE_SCALE);
            }
        }
        if (DebugLayer.BRAIN.in(view.layers()) && person != null) {
            drawBrain(view, person, partialTick, line);
        }
        if (DebugLayer.MEMORY.in(view.layers())) {
            drawBeliefs(view);
        }
        if (DebugLayer.PEERS.in(view.layers())) {
            drawPeers(view, person, level, partialTick);
        }
    }

    /**
     * The walked plan: a polyline from their feet through every remaining waypoint, each leg
     * coloured by how they mean to enter it. Legs already behind are drawn faint rather than
     * dropped — seeing where they came from is half of reading a path that went wrong.
     */
    private static void drawPath(DebugViewPayload view, @Nullable Entity person, float partialTick) {
        List<DebugViewPayload.Step> path = view.path();
        view.goal().ifPresent(goal ->
                Gizmos.cuboid(goal, GizmoStyle.stroke(GOAL_COLOR, THIN)).setAlwaysOnTop());
        if (path.isEmpty()) {
            return;
        }
        int index = Mth.clamp(view.pathIndex(), 0, path.size());
        // Behind them: waypoint to waypoint, faded. Not chained through the current
        // position — those cells are left behind, and hanging them off the feet would draw a long
        // line backwards to the start of the path.
        for (int i = 1; i < index; i++) {
            leg(centre(path.get(i - 1).pos()), path.get(i), true, false);
        }
        if (index >= path.size()) {
            return; // arrived: the whole plan is behind them
        }
        // The leg being walked NOW starts at the actual feet, so it tracks them between
        // snapshots instead of jumping cell to cell at the send cadence.
        // With no body to anchor to (they are outside render distance), fall back to the waypoint
        // before this one so the leg still has a direction to show.
        Vec3 from = person != null
                ? person.getPosition(partialTick).add(0.0, PATH_Y, 0.0)
                : centre(path.get(index > 0 ? index - 1 : index).pos());
        leg(from, path.get(index), false, true);
        for (int i = index + 1; i < path.size(); i++) {
            leg(centre(path.get(i - 1).pos()), path.get(i), false, false);
        }
    }

    /** One leg of the path: the line into {@code step}, coloured by how they mean to enter it. */
    private static void leg(Vec3 from, DebugViewPayload.Step step, boolean walked, boolean current) {
        Vec3 to = centre(step.pos());
        int color = fade(moveColor(step.move()), walked);
        Gizmos.line(from, to, color, current ? CURRENT_LEG_WIDTH : PATH_WIDTH);
        Gizmos.point(to, color, current ? 0.18F : 0.10F);
    }

    /**
     * The arbiter's reasoning, stacked over their head — one gizmo per line.
     *
     * <p>A text gizmo is a SINGLE line: an embedded newline renders as nothing. The server sends
     * the readout already split ({@code BrainDriver.describeLines}) rather than a joined string,
     * because the separators are a chat format's detail and parsing them back out here went
     * quietly wrong whenever that format changed.
     */
    private static void drawBrain(
            DebugViewPayload view, Entity person, float partialTick, int[] line) {
        boolean first = true;
        for (String text : view.brain()) {
            if (text.isBlank()) {
                continue;
            }
            // The first line is "auto"/"manual" — the headline fact of who is driving.
            overhead(person, partialTick, line[0]++, text,
                    BRAIN_TEXT_COLOR, first ? TITLE_SCALE : DETAIL_SCALE);
            first = false;
        }
    }

    /**
     * One stacked line of floating text above a Person's head.
     *
     * <p>Not {@code Gizmos.billboardTextOverMob}, which anchors at
     * {@code getBlockX() + 0.5} / {@code getBlockZ() + 0.5} and the raw {@code getY()}: over a
     * WALKING Person that snaps from block centre to block centre. This keeps vanilla's layout —
     * spacing, left alignment, always-on-top — off their interpolated position instead.
     *
     * <p>Height comes from the body's own box, not vanilla's flat {@code 2.4}, so it follows a
     * crouch and clears the name tag every Person carries (vanilla's debug targets have none).
     */
    private static void overhead(Entity person, float partialTick, int line,
                                 String text, int color, float scale) {
        Vec3 at = above(person.getPosition(partialTick), person.getBbHeight(), line);
        Gizmos.billboardText(text, at, TextGizmo.Style.forColor(color)
                        .withScale(scale)
                        .withLeftAlignment(TEXT_LEFT_ALIGN))
                .setAlwaysOnTop();
    }

    /**
     * Where line {@code line} of a text stack belongs for a body of {@code height} standing at
     * {@code feet} — the single answer to "put this above them".
     *
     * <p>Every label anchored to a body goes through here: text drawn AT a body's position competes
     * with the skin behind it and with the name tag every Person carries.
     */
    private static Vec3 above(Vec3 feet, double height, int line) {
        return new Vec3(feet.x,
                feet.y + height + NAME_TAG_CLEARANCE + line * TEXT_LINE_STEP,
                feet.z);
    }

    /**
     * What they believe is out there: the bounds box they remember and a marker on the anchor they
     * would actually walk to. A stale belief greys out — the ghost of a grove since chopped down is
     * the knowledge store working correctly.
     */
    private static void drawBeliefs(DebugViewPayload view) {
        for (DebugViewPayload.Belief belief : view.beliefs()) {
            int color = belief.stale() ? GHOST_COLOR : kindColor(belief.kind());
            Gizmos.cuboid(AABB.encapsulatingFullBlocks(belief.min(), belief.max()),
                    GizmoStyle.stroke(color, THIN));
            // The anchor gets a stub of a column so it stays findable inside a big bounds box.
            Vec3 anchor = centre(belief.anchor());
            Gizmos.line(anchor, anchor.add(0.0, 1.5, 0.0), color, PATH_WIDTH);
            Gizmos.point(anchor, color, 0.2F);
        }
    }

    /**
     * Who they know about, and the eyes that found them: a line per peer from their own eyes,
     * coloured by which channel is carrying the perception, plus the view cone at its configured
     * angle and range. A REMEMBERED peer's line points at where they last SAW them.
     */
    private static void drawPeers(DebugViewPayload view, @Nullable Entity person,
                                  ClientLevel level, float partialTick) {
        if (person == null) {
            return;
        }
        Vec3 eye = person.getEyePosition(partialTick);
        drawCone(eye, person.getYHeadRot(), view.coneDegrees(), view.senseRadius());
        for (DebugViewPayload.PeerMark peer : view.peers()) {
            int color = awarenessColor(peer.awareness());
            // The live body when the server sent one (a SEEN peer, whose cell is a live sample and
            // so may as well be drawn smoothly); otherwise the believed cell exactly as sent, which
            // for a HEARD or REMEMBERED peer is the whole point.
            Entity body = peer.entityId() == DebugViewPayload.PeerMark.NO_BODY
                    ? null
                    : level.getEntity(peer.entityId());
            Vec3 feet = body != null ? body.getPosition(partialTick) : floorCentre(peer.pos());
            double height = body != null ? body.getBbHeight() : ASSUMED_BODY_HEIGHT;
            // The LINE aims at mid-body — one drawn to the feet reads as pointing straight past
            // someone — but the LABEL goes above the head, clear of the skin it describes, with
            // the same clearance the selected Person's own stack uses.
            Gizmos.line(eye, feet.add(0.0, height * 0.5, 0.0), color, PATH_WIDTH).setAlwaysOnTop();
            // Two lines: who they think it is, then the whole reading — the name alone answers
            // only "is someone there".
            peerText(peer.name(), above(feet, height, 1), color, TITLE_SCALE);
            peerText(detail(peer), above(feet, height, 0), color, DETAIL_SCALE);
        }
    }

    /** The horizontal view cone: its two edges, and an arc closing them at sense range. */
    private static void drawCone(Vec3 eye, float headYaw, int coneDegrees, int radius) {
        if (coneDegrees <= 0 || radius <= 0) {
            return;
        }
        double half = Math.toRadians(coneDegrees / 2.0);
        // Minecraft yaw is degrees clockwise from south with -Z as north, so the facing vector is
        // (-sin, cos) — the same convention the sensor's own cone test uses.
        double facing = Math.toRadians(headYaw);
        Vec3 left = rim(eye, facing - half, radius);
        Vec3 right = rim(eye, facing + half, radius);
        Gizmos.line(eye, left, CONE_COLOR, THIN);
        Gizmos.line(eye, right, CONE_COLOR, THIN);
        Vec3 previous = left;
        for (int i = 1; i <= CONE_SEGMENTS; i++) {
            Vec3 next = rim(eye, facing - half + (2 * half * i / CONE_SEGMENTS), radius);
            Gizmos.line(previous, next, CONE_COLOR, THIN);
            previous = next;
        }
    }

    /** A point on the cone's rim: flat, at eye height, {@code radius} out along {@code angle}. */
    private static Vec3 rim(Vec3 eye, double angle, int radius) {
        return eye.add(-Math.sin(angle) * radius, 0.0, Math.cos(angle) * radius);
    }

    /** A centred, always-visible line of peer text. */
    private static void peerText(String text, Vec3 at, int color, float scale) {
        Gizmos.billboardText(text, at,
                        TextGizmo.Style.forColorAndCentered(color).withScale(scale))
                .setAlwaysOnTop();
    }

    /**
     * The reading under a peer's name: everything they have on them, then how they have it.
     *
     * <p>The awareness tag follows the chat readouts' convention: SEEN is unmarked, {@code [heard]}
     * and {@code [remembered]} are called out, because those are the readings that can be wrong.
     * Spelled out as well as colour-coded: a grey line reads as stale only beside a green one.
     */
    private static String detail(DebugViewPayload.PeerMark peer) {
        Peer.Awareness[] values = Peer.Awareness.values();
        Peer.Awareness awareness = peer.awareness() >= 0 && peer.awareness() < values.length
                ? values[peer.awareness()]
                : Peer.Awareness.REMEMBERED;
        String tag = awareness == Peer.Awareness.SEEN
                ? ""
                : " [" + awareness.name().toLowerCase(Locale.ROOT) + "]";
        return String.format(Locale.ROOT, "%s%s · %.1fm", peer.tell(), tag, peer.distance());
    }

    private static int moveColor(int move) {
        MoveType[] moves = MoveType.values();
        if (move < 0 || move >= moves.length) {
            return WALK_COLOR;
        }
        return switch (moves[move]) {
            case WALK -> WALK_COLOR;
            case JUMP -> JUMP_COLOR;
            case DROP -> DROP_COLOR;
            case LEAP -> LEAP_COLOR;
            case SWIM -> SWIM_COLOR;
        };
    }

    private static int kindColor(int kind) {
        PoiKind[] kinds = PoiKind.values();
        if (kind < 0 || kind >= kinds.length) {
            return GHOST_COLOR;
        }
        return switch (kinds[kind]) {
            case TREE -> TREE_COLOR;
            case WATER -> WATER_COLOR;
        };
    }

    private static int awarenessColor(int awareness) {
        Peer.Awareness[] values = Peer.Awareness.values();
        if (awareness < 0 || awareness >= values.length) {
            return REMEMBERED_COLOR;
        }
        return switch (values[awareness]) {
            case SEEN -> SEEN_COLOR;
            case HEARD -> HEARD_COLOR;
            case REMEMBERED -> REMEMBERED_COLOR;
        };
    }

    /** Drops a colour to a quarter alpha — how the already-walked part of the path is drawn. */
    private static int fade(int argb, boolean faded) {
        if (!faded) {
            return argb;
        }
        int alpha = Mth.clamp((argb >>> 24) / 4, 16, 255);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** The centre of a cell, lifted just off its floor — where a waypoint line is drawn through. */
    private static Vec3 centre(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + PATH_Y, pos.getZ() + 0.5);
    }

    /** The exact floor centre of a cell — where a body standing in it has its feet. */
    private static Vec3 floorCentre(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }
}
