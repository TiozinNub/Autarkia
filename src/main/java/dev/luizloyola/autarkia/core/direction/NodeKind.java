package dev.luizloyola.autarkia.core.direction;

/**
 * Whether a node is an age or a side objective. Being core means three things: the party's age is
 * its deepest core node, work toward one outbids work toward a side node, and a core node never
 * depends on a side one — so a party that ignores every side objective still climbs.
 */
public enum NodeKind {
    CORE,
    SIDE
}
