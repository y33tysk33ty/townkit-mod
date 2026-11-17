package townkit.core.brain;

import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import townkit.api.Subscription;
import townkit.api.brain.ChoiceProvider;
import townkit.api.brain.InterruptProvider;
import townkit.api.brain.VillagerActivity;
import townkit.api.spi.TownKitBrainView;
import townkit.api.village.Villager;
import townkit.core.village.VillagerHandle;

/** The backend side of {@link townkit.api.brain.TownKitBrain}. */
public final class CoreBrain implements TownKitBrainView {
    @Override
    public Subscription registerActivity(ResourceLocation id, Function<Villager, VillagerActivity> factory) {
        Subscription s = ActivityRegistry.register(id, factory);
        BrainHooks.ensureAttached();
        return new Subscription() {
            @Override
            public void remove() {
                s.remove();
                BrainHooks.detachIfUnused();
            }

            @Override
            public boolean isDisabled() {
                return s.isDisabled();
            }
        };
    }

    @Override
    public Subscription addChoice(String owner, ChoiceProvider provider) {
        return ChoiceHooks.add(owner, provider);
    }

    @Override
    public Subscription addInterrupt(String owner, InterruptProvider provider, int intervalTicks) {
        return InterruptHooks.add(owner, provider, intervalTicks);
    }

    /** For TownKit's tests: queue a run of {@code id} on this villager the way Towntopia queues its own activities. */
    public static boolean start(Villager villager, ResourceLocation id) {
        if (!(villager instanceof VillagerHandle h)) return false;
        VillagerModel model = h.model();
        if (model == null) return false;
        Activity a = ActivityRegistry.create(id, model);
        if (a == null) return false;
        model.brain().forceActivity(a);
        return true;
    }
}
