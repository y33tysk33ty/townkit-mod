package townkit.core.nav;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;
import townkit.api.nav.RideContext;
import townkit.api.nav.TransportLink;
import townkit.api.village.Villager;

/**
 * The {@link RideContext} of one ride ({@link RideEngine}), server thread only. {@link #link()} is oriented the way
 * the villager travels: {@code from()} is where it boarded, {@code to()} where it gets off.
 */
final class CoreRideContext implements RideContext {
    private final Villager villager;
    private final NpcEntity body;
    private final ServerLevel level;
    private final TransportLink link;
    private final VillageManager manager;
    /** Ride ticks so far; set by {@link RideEngine}. */
    int ticks;

    CoreRideContext(Villager villager, NpcEntity body, ServerLevel level, TransportLink link, VillageManager manager) {
        this.villager = villager;
        this.body = body;
        this.level = level;
        this.link = link;
        this.manager = manager;
    }

    VillageManager manager() {
        return this.manager;
    }

    @Override
    public Villager villager() {
        return this.villager;
    }

    @Override
    public PathfinderMob body() {
        return this.body;
    }

    @Override
    public ServerLevel level() {
        return this.level;
    }

    @Override
    public TransportLink link() {
        return this.link;
    }

    @Override
    public int ticks() {
        return this.ticks;
    }

    @Override
    public void moveTowards(Vec3 target, double speed) {
        this.body.getMoveControl().setWantedPosition(target.x, target.y, target.z, speed);
    }

    /** Stands the body on the tile's surface (Towntopia's {@code standY} when the route map has the tile). */
    @Override
    public void placeAt(BlockPos feet) {
        this.body.getNavigation().stop();
        this.body.teleportTo(feet.getX() + 0.5, standY(this.manager, feet), feet.getZ() + 0.5);
        this.body.setDeltaMovement(Vec3.ZERO);
        this.body.resetFallDistance();
    }

    /** Where a body stands on the tile whose feet are at {@code feet}: the node's {@code standY}, else the feet. */
    static double standY(@Nullable VillageManager manager, BlockPos feet) {
        NavGraph graph = manager == null ? null : manager.getGraph();
        NavNode node = graph == null ? null : graph.nodeAt(feet.getX(), feet.getY(), feet.getZ());
        return node != null ? node.standY : feet.getY();
    }
}
