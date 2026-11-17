package townkit.api.spi;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.Subscription;
import townkit.api.bubble.BubbleIcon;
import townkit.api.bubble.BubbleKind;
import townkit.api.bubble.BubbleState;
import townkit.api.bubble.Valence;
import townkit.api.village.Villager;

/** What {@link townkit.api.bubble.TownKitBubbles} asks TownKit's backend for. Addons use TownKitBubbles instead. */
@ApiStatus.Internal
public interface TownKitBubblesView {
    BubbleIcon icon(ResourceLocation id, Valence valence);

    BubbleIcon towntopia(String id);

    List<BubbleIcon> builtIns();

    boolean show(Entity villager, BubbleIcon icon, BubbleKind kind, int ticks, boolean silent);

    void clear(Entity villager);

    Subscription state(String owner, BubbleState state);

    /** A hide ({@code replacement} null) or a swap of {@code builtIn}, for {@code villager} or (null) every villager. */
    Subscription rule(String owner, @Nullable Villager villager, BubbleIcon builtIn, @Nullable BubbleIcon replacement);

    Subscription addIdleThought(String owner, BubbleIcon icon, int weight);
}
