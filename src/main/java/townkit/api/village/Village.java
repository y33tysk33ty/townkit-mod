package townkit.api.village;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * A Towntopia village. Safe to keep: it holds the village's id and reads Towntopia live on every call, so once the
 * village is gone {@link #exists()} is false and the other answers are empty. Server thread only.
 *
 * @since 0.3.0
 */
public interface Village {
    UUID id();

    ResourceKey<Level> dimension();

    boolean exists();

    /** Inside the village's border. */
    boolean contains(BlockPos pos);

    Collection<Villager> villagers();

    Optional<Villager> villager(UUID id);

    Collection<BuildingInfo> buildings();

    /**
     * The player who owns (claimed) the village, read live; empty when nobody owns it or the village is gone.
     * Server thread only.
     *
     * @since 0.6.2
     */
    Optional<UUID> owner();

    /**
     * The owner's name as Towntopia last saw it, read live; empty when the village has no owner, Towntopia has no name
     * for them, or the village is gone. Server thread only.
     *
     * @since 0.6.2
     */
    Optional<String> ownerName();

    /**
     * What the village is doing now, read live. {@link VillageState#INACTIVE} once the village is gone.
     * Server thread only.
     *
     * @since 0.6.2
     */
    VillageState state();

    /**
     * The village bell, read live; empty when the village has no bell or is gone. Server thread only.
     *
     * @since 0.6.2
     */
    Optional<BlockPos> bellPos();

    /**
     * Where the village's raid is, read live. {@link RaidStage#IDLE} when there is no raid, when Towntopia hasn't
     * loaded the village's raid state yet, or once the village is gone. Server thread only.
     *
     * @since 0.6.2
     */
    RaidStage raidStage();

    /**
     * True during fighting or a Bad Omen countdown. False during a normal raid's WARNING and CAMP, so it can go true,
     * false, true within one raid when raiders pull back to their camp. For "any raid at all" use
     * {@code raidStage() != IDLE}. Read live; false when the village is gone. Server thread only.
     *
     * @since 0.6.2
     */
    boolean raidInProgress();

    /**
     * The village's border as a box (full world height); empty when Towntopia has no border for it yet or the village
     * is gone. A copy, safe to keep. Server thread only.
     *
     * @since 0.6.2
     */
    Optional<AABB> border();

    /**
     * A read-only snapshot of what the village's storage holds right now (items, categories, emeralds by kind). Reads
     * only chunks that are already loaded, never loads one, and never takes anything. An empty snapshot (all zero)
     * once the village is gone. Server thread only.
     *
     * @since 0.6.2
     */
    VillageStorage storage();
}
