package townkit.core.brain;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Rules for moving to a goal or following a target: pure, unit-tested. */
public final class TargetFollow {
    static final double REACH = 2.0;
    static final double REISSUE_DISTANCE = 2.0;
    static final long REISSUE_DELAY = 20;
    static final double PROGRESS = 0.5;
    static final long STUCK_AFTER = 200;

    private TargetFollow() {
    }

    /** Ask for a new route to a moving target: it moved more than 2 blocks, and at least 20 ticks since the last ask. */
    public static boolean shouldReissue(BlockPos lastGoal, BlockPos targetNow, long lastIssueTick, long now) {
        return now - lastIssueTick >= REISSUE_DELAY && Math.sqrt(lastGoal.distSqr(targetNow)) > REISSUE_DISTANCE;
    }

    /**
     * A new position goal asks Towntopia for a new route (an off-thread A* search) only when it is more than 2 blocks
     * from the goal already asked for and at least 20 ticks have passed; otherwise the move carries on.
     */
    public static boolean newGoalNeedsRequest(@Nullable BlockPos current, BlockPos next, long lastIssueTick, long now) {
        if (current == null) return true;
        return Math.sqrt(current.distSqr(next)) > REISSUE_DISTANCE && now - lastIssueTick >= REISSUE_DELAY;
    }

    public static boolean arrived(Vec3 villager, Vec3 goal) {
        return villager.distanceTo(goal) <= REACH;
    }

    /** An arrived follow walks again once the target is more than 2 blocks past reach (so it doesn't flicker at the edge). */
    public static boolean followResumes(Vec3 villager, Vec3 target) {
        return villager.distanceTo(target) > REACH + REISSUE_DISTANCE;
    }

    /** No 0.5 blocks of progress towards the goal for 200 ticks. */
    public static boolean stuck(double bestDistance, double distanceNow, long lastProgressTick, long now) {
        return bestDistance - distanceNow < PROGRESS && now - lastProgressTick >= STUCK_AFTER;
    }
}
