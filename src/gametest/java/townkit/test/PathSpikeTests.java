package townkit.test;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.FollowWalk;
import com.alex.test.village.NavNode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import townkit.api.hooks.HookListener;
import townkit.core.FieldReader;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.test.BehaviourTests.TestVillage;

/**
 * Small helpers the path tests share (this class first held early pathfinding experiments). Its three
 * parts are covered by {@link PathTests}: a walk cut at a link resumes with REPLAN and water rescue is off for one
 * villager, a ride carries a villager up a bubble column.
 */
public final class PathSpikeTests {
    private PathSpikeTests() {
    }

    static final String END_OF_ROUTE = "village.FollowWalk#endOfRoute(PathfinderMob,ServerLevel,String)";
    static final String WALK_TICK = "village.FollowWalk#tick(PathfinderMob)";
    static final String STUCK_LOOP = "village.VillagerModel#noteStuckLoop(NavGraph)";

    static final FieldReader<Byte> CUT_KIND = FieldReader.of(FollowWalk.class, "cutKind", byte.class);
    static final FieldReader<NavNode> CUT_FROM = FieldReader.of(FollowWalk.class, "cutFrom", NavNode.class);
    static final FieldReader<NavNode> CUT_TO = FieldReader.of(FollowWalk.class, "cutTo", NavNode.class);

    static Hooks.Entry hook(String key, boolean after, HookListener listener) {
        HookInfo h = HookManifest.get().byKey.get(key);
        if (h == null) throw new IllegalStateException("no hook " + key);
        return Hooks.add(h, "townkit-spike", listener, after);
    }

    /** Sets a block and tells Towntopia's route map about it (a plain setBlock isn't seen by its dirty tracker). */
    static void place(TestVillage tv, ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 3);
        tv.mgr().markNavigationDirtyAt(pos);
    }

    static boolean at(NavNode n, BlockPos p) {
        return n != null && n.x == p.getX() && n.feetY == p.getY() && n.z == p.getZ();
    }

    static String fmt(NavNode n) {
        return n == null ? "null" : n.x + "," + n.feetY + "," + n.z;
    }

    static double dist(NpcEntity npc, BlockPos p) {
        return npc.position().distanceTo(Vec3.atBottomCenterOf(p));
    }
}
