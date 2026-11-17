package townkit.api.spi;

import java.util.List;
import townkit.api.Subscription;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.anim.Move;
import townkit.api.anim.PlayMode;
import townkit.api.anim.Prop;
import townkit.api.anim.TownKitAnimations;
import townkit.api.village.Villager;

/** What {@link TownKitAnimations} asks TownKit's backend for. Addons use TownKitAnimations instead. */
@ApiStatus.Internal
public interface TownKitAnimationsView {
    TownKitAnimations.PlayResult play(String owner, Entity entity, Move move, PlayMode mode);

    TownKitAnimations.PlayResult play(String owner, Villager villager, Move move, PlayMode mode);

    void stop(Entity entity);

    void stop(String owner, Entity entity);

    void stop(String owner, Villager villager);

    Subscription replace(String owner, Move builtIn, Move replacement);

    Subscription replace(String owner, Villager villager, Move builtIn, Move replacement);

    Subscription withProps(String owner, Move move, List<Prop> props);

    Subscription onClientMove(String owner, TownKitAnimations.ClientMoveListener listener);
}
