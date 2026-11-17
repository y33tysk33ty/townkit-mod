package townkit.api.hooks;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import townkit.api.brain.ActivityContext;
import java.util.Optional;
import javax.annotation.Nullable;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.spi.TownKitBackend;
import townkit.api.village.Village;
import townkit.api.village.Villager;

/**
 * Escape hatch from the stable handles to Towntopia's own objects, for when the stable API has no answer yet.
 * Experimental: tied to Towntopia's own names; may break on any Towntopia update. Prefer the stable API in townkit.api.
 */
@ApiStatus.Experimental
public final class Unwrap {
    private Unwrap() {
    }

    /** The village's VillageManager; null once the village is gone. Throws IllegalArgumentException for a handle TownKit didn't make. */
    @Nullable
    public static VillageManager village(Village village) {
        return TownKitBackend.get().unwrap(village);
    }

    /** The villager's VillagerModel; null once the villager is gone (or for a client-side villager). */
    @Nullable
    public static VillagerModel villager(Villager villager) {
        return TownKitBackend.get().unwrap(villager);
    }

    /** Towntopia's own context behind an addon activity's context, during its call; null outside one. @since 0.4.0 */
    @Nullable
    public static Activity.Context context(ActivityContext ctx) {
        return TownKitBackend.get().unwrap(ctx);
    }

    /** The villager's entity while it's loaded. */
    public static Optional<NpcEntity> npc(Villager villager) {
        return TownKitBackend.get().npc(villager);
    }
}
