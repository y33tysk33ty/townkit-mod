package townkit.core.nav;

/**
 * One place in Towntopia's classes where it reads passability, step cost, walking pace or the flood-fill queue.
 *
 * @param owner  class, relative to {@code com/alex/test/}
 * @param method method name plus descriptor
 * @param kind   what the instruction is
 */
public record NavSite(String owner, String method, Kind kind) {
    public enum Kind {
        /** {@code INVOKEVIRTUAL village/NavNode.canStepTo(NavNode)Z}. */
        STEP,
        /** {@code INVOKESTATIC village/AStarPathfinder.edgeCost(...)I}. */
        COST,
        /** {@code INVOKEVIRTUAL MoveControl.setWantedPosition(DDDD)V}, in {@code village/FollowWalk} only. */
        PACE,
        /** {@code INVOKEINTERFACE java/util/Queue.poll()}, in {@code village/NavGraph#floodFillInner} only. */
        POLL,
        /**
         * {@code INVOKESTATIC village/AStarPathfinder.lineOfSight(...)Z}, in {@code village/AStarPathfinder#smooth}
         * only: the smoothing shortcut, which may cross a tile the traced route avoided.
         */
        LOS,
        /**
         * {@code IRETURN} in {@code village/NavCells$1#kind(NavNode,NavNode,long)} only: the reachability adapter's
         * step kind (-1 no step, 0 open, 1 blocked for now, 2 routable to non-routable).
         */
        CELL
    }

    /** {@code owner#method#kind}: the row key in {@code nav-sites.tsv}. */
    public String key() {
        return owner + "#" + method + "#" + kind;
    }
}
