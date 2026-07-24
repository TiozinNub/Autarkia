package dev.luizloyola.autarkia.core.brain.board;

import dev.luizloyola.autarkia.core.inv.ItemSpec;

/**
 * One claimable unit of work: acquire {@code count} of {@code spec} at a board-set
 * {@code priority}, on the same 0..1 demand scale instincts bid on. Identity + goal + price only —
 * the lifecycle (posted → claimed → completed/failed) is the board's, the execution (an
 * {@code ObtainItem} root) the arbiter's.
 */
public record WorkItem(String id, ItemSpec spec, int count, double priority) {
    /** The journal/board line name: {@code "acquire logs x16"}. */
    public String describe() {
        return "acquire " + spec.name() + " x" + count;
    }
}
