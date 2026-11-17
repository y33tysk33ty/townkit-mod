package townkit.core.brain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TargetFollowTest {
    @Test
    void reissueOnlyAfterMoveAndDelay() {
        BlockPos last = new BlockPos(0, 64, 0);
        assertFalse(TargetFollow.shouldReissue(last, new BlockPos(3, 64, 0), 100, 119));
        assertTrue(TargetFollow.shouldReissue(last, new BlockPos(3, 64, 0), 100, 120));
        assertFalse(TargetFollow.shouldReissue(last, new BlockPos(1, 64, 0), 100, 200));
    }

    /** A new position goal asks Towntopia for a route only if it moved more than 2 blocks and 20 ticks have passed. */
    @Test
    void newGoalRateLimited() {
        BlockPos old = new BlockPos(0, 64, 0);
        assertFalse(TargetFollow.newGoalNeedsRequest(old, new BlockPos(1, 64, 1), 100, 500));
        assertFalse(TargetFollow.newGoalNeedsRequest(old, new BlockPos(10, 64, 0), 100, 119));
        assertTrue(TargetFollow.newGoalNeedsRequest(old, new BlockPos(10, 64, 0), 100, 120));
        assertTrue(TargetFollow.newGoalNeedsRequest(null, new BlockPos(10, 64, 0), 100, 101));
    }

    @Test
    void arrivedWithinReach() {
        assertTrue(TargetFollow.arrived(new Vec3(0, 64, 0), new Vec3(2.0, 64, 0)));
        assertFalse(TargetFollow.arrived(new Vec3(0, 64, 0), new Vec3(2.01, 64, 0)));
    }

    /** Following an entity: an arrived follow walks again once the target is more than 4 blocks away (reach + 2). */
    @Test
    void followResumesBeyondReachPlusSlack() {
        assertFalse(TargetFollow.followResumes(new Vec3(0, 64, 0), new Vec3(4.0, 64, 0)));
        assertTrue(TargetFollow.followResumes(new Vec3(0, 64, 0), new Vec3(4.01, 64, 0)));
    }

    @Test
    void stuckAfterTimeout() {
        assertFalse(TargetFollow.stuck(10.0, 10.0, 0, 199));
        assertTrue(TargetFollow.stuck(10.0, 10.0, 0, 200));
        assertFalse(TargetFollow.stuck(10.0, 9.5, 0, 500)); // 0.5 blocks of progress
    }
}
