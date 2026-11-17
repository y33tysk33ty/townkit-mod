package townkit.api.spi;

import java.util.List;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.Subscription;
import townkit.api.skin.Look;
import townkit.api.skin.SkinRef;
import townkit.api.village.Villager;

/** What {@link townkit.api.skin.TownKitSkins} asks TownKit's backend for. Addons use TownKitSkins instead. */
@ApiStatus.Internal
public interface TownKitSkinsView {
    List<Look> looks();

    Optional<Look> currentLook();

    Subscription set(String owner, Villager villager, SkinRef skin);

    Subscription setTemporary(String owner, Entity entity, SkinRef skin);

    Optional<SkinRef> of(Entity entity);
}
