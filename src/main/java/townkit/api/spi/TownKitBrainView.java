package townkit.api.spi;

import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.Subscription;
import townkit.api.brain.ChoiceProvider;
import townkit.api.brain.InterruptProvider;
import townkit.api.brain.VillagerActivity;
import townkit.api.village.Villager;

/** What {@link townkit.api.brain.TownKitBrain} asks TownKit's backend for. Addons use TownKitBrain instead. */
@ApiStatus.Internal
public interface TownKitBrainView {
    Subscription registerActivity(ResourceLocation id, Function<Villager, VillagerActivity> factory);

    Subscription addChoice(String owner, ChoiceProvider provider);

    Subscription addInterrupt(String owner, InterruptProvider provider, int intervalTicks);
}
