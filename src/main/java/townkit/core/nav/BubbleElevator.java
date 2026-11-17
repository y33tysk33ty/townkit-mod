package townkit.core.nav;

import com.alex.test.village.NavGraph;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import townkit.api.nav.Ride;
import townkit.api.nav.RideContext;
import townkit.api.nav.RideStatus;
import townkit.api.nav.TownKitTransport;
import townkit.api.nav.TransportKind;
import townkit.api.nav.TransportLink;
import townkit.api.nav.TransportStop;

/**
 * TownKit's ready-made bubble elevator ({@link TownKitTransport#BUBBLE_ELEVATOR}). Registered by TownKit but off
 * until a mod enables it with its own marker item.
 *
 * <p>Stops and links:
 * <ul>
 *   <li>A column is 2 to 64 blocks of {@code minecraft:bubble_column} on soul sand (the link goes one way up) or magma
 *       (one way down). Only a whole column counts: a stop beside its middle links to nothing.</li>
 *   <li>The bottom stop's tile is beside the column's bottom block, at the same height (the villager walks straight
 *       in). Water flows out of a bubble column into an open side, so this opening needs something that holds the
 *       water and can be walked through, such as a sign at the feet and a hanging sign at the head.</li>
 *   <li>The top stop's tile stands on a block beside the column's top block (its feet one above the top block).</li>
 *   <li>Both stop tiles must be dry and walkable ({@code NavGraph.isDryWalkable}), and in loaded chunks. Each bottom
 *       stop of a column links to each top stop of the same column. Cost: the column's height x 2.</li>
 *   <li>{@code available}: the column is still there, whole, with the same direction (re-read at each refresh, about
 *       one block read per column block; false while a stop's chunk isn't loaded).</li>
 * </ul>
 *
 * <p>The ride: the villager steps into the column (moved towards its centre for at most 20 ticks), is then held on
 * the column's centre across while the bubbles carry it, and is put on the far stop's tile once its feet are within 1
 * block of it (bubbles alone leave it below the top tile). It fails when it is more than 0.6 blocks off the column's
 * centre across for 10 ticks in a row (after the 20 boarding ticks), and after {@code height x 8 + 60} ticks.
 * Draining the column or breaking a stop ends a ride through TownKit's own checks (the link stops being rideable).
 */
final class BubbleElevator implements TransportKind {
    static final int MIN_HEIGHT = 2;
    static final int MAX_HEIGHT = 64;
    static final int BOARD_TICKS = 20;
    static final double OFF_COLUMN = 0.6;
    static final int OFF_TICKS = 10;

    /** One whole column: its x/z, its lowest and highest bubble block, and its direction. */
    record Column(int x, int z, int bottom, int top, boolean up) {
        int height() {
            return top - bottom + 1;
        }
    }

    @Override
    public ResourceLocation id() {
        return TownKitTransport.BUBBLE_ELEVATOR;
    }

    @Override
    public List<TransportLink> links(ServerLevel level, List<TransportStop> stops) {
        Map<Column, List<BlockPos>> bottoms = new LinkedHashMap<>();
        Map<Column, List<BlockPos>> tops = new LinkedHashMap<>();
        for (TransportStop s : stops) {
            BlockPos tile = s.tile();
            if (!level.isLoaded(tile) || !NavGraph.isDryWalkable(level, tile)) {
                continue;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side = tile.relative(d);
                Column c = columnAt(level, side);
                if (c != null && c.bottom() == tile.getY()) {
                    bottoms.computeIfAbsent(c, k -> new ArrayList<>()).add(tile);
                }
                c = columnAt(level, side.below());
                if (c != null && c.top() == tile.getY() - 1) {
                    tops.computeIfAbsent(c, k -> new ArrayList<>()).add(tile);
                }
            }
        }
        List<TransportLink> out = new ArrayList<>();
        for (Map.Entry<Column, List<BlockPos>> e : bottoms.entrySet()) {
            Column c = e.getKey();
            for (BlockPos b : e.getValue()) {
                for (BlockPos t : tops.getOrDefault(c, List.of())) {
                    out.add(c.up() ? new TransportLink(id(), b, t, c.height() * 2, false)
                            : new TransportLink(id(), t, b, c.height() * 2, false));
                }
            }
        }
        return out;
    }

    @Override
    public boolean available(ServerLevel level, TransportLink link) {
        return columnOf(level, link) != null;
    }

    @Override
    public Ride ride(RideContext ctx) {
        return new ColumnRide(columnOf(ctx.level(), ctx.link()));
    }

    /** The column {@code link} rides (oriented either way), when it is still whole and goes the link's way; else null. */
    @Nullable
    static Column columnOf(Level level, TransportLink link) {
        boolean up = link.from().getY() < link.to().getY();
        BlockPos low = up ? link.from() : link.to();
        BlockPos high = up ? link.to() : link.from();
        if (!level.isLoaded(low) || !level.isLoaded(high)) {
            return null;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = low.relative(d);
            if (Math.abs(side.getX() - high.getX()) + Math.abs(side.getZ() - high.getZ()) != 1) {
                continue;
            }
            Column c = columnAt(level, side);
            if (c != null && c.up() == up && c.bottom() == low.getY() && c.top() == high.getY() - 1) {
                return c;
            }
        }
        return null;
    }

    /**
     * The whole column {@code pos} is part of, or null: {@code pos} is a bubble column block, the run of bubble blocks
     * through it is 2 to 64 high, all one direction, on soul sand (up) or magma (down).
     */
    @Nullable
    static Column columnAt(Level level, BlockPos pos) {
        BlockState at = level.getBlockState(pos);
        if (!at.is(Blocks.BUBBLE_COLUMN)) {
            return null;
        }
        boolean down = at.getValue(BubbleColumnBlock.DRAG_DOWN);
        BlockPos.MutableBlockPos p = pos.mutable();
        int bottom = pos.getY();
        while (true) {
            p.setY(bottom - 1);
            BlockState s = level.getBlockState(p);
            if (!s.is(Blocks.BUBBLE_COLUMN)) {
                if (!s.is(down ? Blocks.MAGMA_BLOCK : Blocks.SOUL_SAND)) {
                    return null;
                }
                break;
            }
            if (s.getValue(BubbleColumnBlock.DRAG_DOWN) != down || pos.getY() - --bottom >= MAX_HEIGHT) {
                return null;
            }
        }
        int top = pos.getY();
        while (true) {
            p.setY(top + 1);
            BlockState s = level.getBlockState(p);
            if (!s.is(Blocks.BUBBLE_COLUMN)) {
                break;
            }
            if (s.getValue(BubbleColumnBlock.DRAG_DOWN) != down || ++top - bottom + 1 > MAX_HEIGHT) {
                return null;
            }
        }
        if (top - bottom + 1 < MIN_HEIGHT) {
            return null;
        }
        return new Column(pos.getX(), pos.getZ(), bottom, top, !down);
    }

    /** One ride through {@code column} (null: the column was gone when the ride started; it fails at once). */
    static final class ColumnRide implements Ride {
        @Nullable
        private final Column column;
        private boolean boarded;
        private int off;

        ColumnRide(@Nullable Column column) {
            this.column = column;
        }

        @Override
        public int timeoutTicks() {
            return column == null ? 1 : column.height() * 8 + 60;
        }

        @Override
        public RideStatus tick(RideContext ctx) {
            if (column == null) {
                return RideStatus.FAILED;
            }
            PathfinderMob body = ctx.body();
            double cx = column.x() + 0.5;
            double cz = column.z() + 0.5;
            Vec3 p = body.position();
            double away = Math.hypot(p.x - cx, p.z - cz);
            if (!boarded) {
                if (away <= OFF_COLUMN) {
                    boarded = true;
                } else if (ctx.ticks() < BOARD_TICKS) {
                    ctx.moveTowards(new Vec3(cx, ctx.link().from().getY(), cz), 1.0);
                    if (body.isInWaterOrBubble()) {
                        // water slows walking to a crawl: help it in
                        Vec3 v = body.getDeltaMovement();
                        body.setDeltaMovement((cx - p.x) / away * 0.15, v.y, (cz - p.z) / away * 0.15);
                    }
                    return RideStatus.RUNNING;
                }
            }
            if (away <= OFF_COLUMN) {
                off = 0;
                body.setPos(cx, p.y, cz);
                body.setDeltaMovement(0, body.getDeltaMovement().y, 0);
                ctx.moveTowards(new Vec3(cx, p.y, cz), 0);
            } else if (++off >= OFF_TICKS) {
                return RideStatus.FAILED;
            }
            BlockPos to = ctx.link().to();
            boolean up = column.up();
            if (up ? body.getY() >= to.getY() - 1 : body.getY() <= to.getY() + 1) {
                ctx.placeAt(to);
                return RideStatus.DONE;
            }
            return RideStatus.RUNNING;
        }
    }
}
