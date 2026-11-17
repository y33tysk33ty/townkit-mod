package townkit.core.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import townkit.api.brain.MoveStatus;

/**
 * One activity run's move: issued once through Towntopia's own route request, then polled on later ticks. A finished
 * move (arrived, failed, stuck) keeps its answer until the activity asks for a different goal, except that an arrived
 * follow walks again when its target gets more than 4 blocks away.
 */
final class MoveTracker {
    @Nullable
    private BlockPos goal;
    @Nullable
    private WeakReference<Entity> target;
    private float speed;
    private long issuedAt;
    private double best;
    private long progressAt;
    @Nullable
    private MoveStatus finished;
    @Nullable
    private String problem;

    Optional<String> problem() {
        return Optional.ofNullable(this.problem);
    }

    MoveStatus toPos(Activity.Context ctx, BlockPos goal, float speed) {
        if (this.target != null || !goal.equals(this.goal)) {
            // a goal that drifts while a move is under way doesn't start a new route search every tick
            boolean underWay = this.target == null && this.finished == null && this.goal != null;
            if (underWay && !TargetFollow.newGoalNeedsRequest(this.goal, goal, this.issuedAt, ctx.level().getGameTime())) {
                return this.poll(ctx, Vec3.atBottomCenterOf(this.goal));
            }
            this.target = null;
            return this.issue(ctx, goal, speed);
        }
        return this.poll(ctx, Vec3.atBottomCenterOf(goal));
    }

    /** Stop the villager if it is still walking to the goal this tracker asked for. */
    void stop(Activity.Context ctx) {
        if (this.goal != null && this.finished == null && this.goal.equals(ctx.model().moveGoal())) ctx.model().stopMoving();
    }

    MoveStatus toEntity(Activity.Context ctx, Entity entity, float speed) {
        if (entity.isRemoved() || !entity.isAlive()) return this.end(MoveStatus.FAILED, "target gone");
        Entity current = this.target == null ? null : this.target.get();
        if (current != entity) {
            this.target = new WeakReference<>(entity);
            return this.issue(ctx, entity.blockPosition(), speed);
        }
        // ARRIVED isn't final while following: the target may walk off again
        if (this.finished == MoveStatus.ARRIVED && ctx.npc() != null && TargetFollow.followResumes(ctx.npc().position(), entity.position())) {
            return this.issue(ctx, entity.blockPosition(), speed);
        }
        long now = ctx.level().getGameTime();
        if (this.finished == null && TargetFollow.shouldReissue(Objects.requireNonNull(this.goal), entity.blockPosition(), this.issuedAt, now)) {
            this.request(ctx, entity.blockPosition(), speed);
            if (this.finished != null) return this.finished;
        }
        return this.poll(ctx, entity.position());
    }

    private MoveStatus issue(Activity.Context ctx, BlockPos goal, float speed) {
        this.finished = null;
        this.problem = null;
        this.speed = speed;
        this.request(ctx, goal, speed);
        if (this.finished != null) return this.finished;
        this.best = distance(ctx.npc(), Vec3.atBottomCenterOf(goal));
        this.progressAt = ctx.level().getGameTime();
        return this.poll(ctx, this.target != null && this.target.get() != null ? this.target.get().position() : Vec3.atBottomCenterOf(goal));
    }

    /** Ask Towntopia for a route; refusals become FAILED with the reason. */
    private void request(Activity.Context ctx, BlockPos goal, float speed) {
        this.goal = goal;
        this.issuedAt = ctx.level().getGameTime();
        NavGraph graph = ctx.mgr() == null ? null : ctx.mgr().getGraph();
        if (graph == null) {
            this.end(MoveStatus.FAILED, "no route map yet");
            return;
        }
        if (!graph.isInsideBounds(goal) || graph.nearestNonAvoided(goal, 3, 3) == null) {
            this.end(MoveStatus.FAILED, "off the route map");
            return;
        }
        if (!ctx.model().requestMoveTo(graph, goal, speed)) this.end(MoveStatus.FAILED, "no route");
    }

    private MoveStatus poll(Activity.Context ctx, Vec3 goalPos) {
        if (this.finished != null) return this.finished;
        NpcEntity npc = ctx.npc();
        if (npc == null) return this.end(MoveStatus.FAILED, "villager not loaded");
        if (TargetFollow.arrived(npc.position(), goalPos)) return this.end(MoveStatus.ARRIVED, null);
        VillagerModel model = ctx.model();
        if (model.hasRecentMoveFailure(Objects.requireNonNull(this.goal))) return this.end(MoveStatus.FAILED, "no route");
        long now = ctx.level().getGameTime();
        double d = distance(npc, goalPos);
        if (this.best - d >= TargetFollow.PROGRESS) {
            this.best = d;
            this.progressAt = now;
        }
        if (TargetFollow.stuck(this.best, d, this.progressAt, now)) return this.end(MoveStatus.STUCK, "stuck");
        // Towntopia's walk ended short of the goal (it stops on the nearest route tile): ask again, at most every 20 ticks
        if (!model.isMoving() && !model.isAwaitingPath() && now - this.issuedAt >= TargetFollow.REISSUE_DELAY) {
            this.request(ctx, this.goal, this.speed);
            if (this.finished != null) return this.finished;
        }
        return MoveStatus.MOVING;
    }

    private MoveStatus end(MoveStatus status, @Nullable String problem) {
        this.finished = status;
        this.problem = problem;
        return status;
    }

    private static double distance(@Nullable NpcEntity npc, Vec3 goal) {
        return npc == null ? Double.MAX_VALUE : npc.position().distanceTo(goal);
    }
}
