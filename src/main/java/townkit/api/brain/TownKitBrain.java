package townkit.api.brain;

import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import townkit.api.Subscription;
import townkit.api.spi.TownKitBrainView;
import townkit.api.spi.TownKitBackend;
import townkit.api.village.Villager;

/**
 * Adding behaviour to Towntopia's villagers: register activities, then let the brain choose them. Nothing is hooked
 * into Towntopia's brain until the first registration, and removing the last one unhooks it again.
 *
 * @since 0.4.0
 */
public final class TownKitBrain {
    private TownKitBrain() {
    }

    private static TownKitBrainView view() {
        return TownKitBackend.get().brain();
    }

    /**
     * Register an activity under {@code id}. The id is saved with a running activity, so it should never change; a
     * saved id nobody registers any more (the addon was removed) is ignored and Towntopia decides afresh. A second
     * registration of the same id throws IllegalArgumentException.
     */
    public static Subscription registerActivity(ResourceLocation id, Function<Villager, VillagerActivity> factory) {
        return view().registerActivity(id, factory);
    }

    /**
     * Offer a registered activity in Towntopia's daytime choice and in place of evening leisure steps (see
     * {@link ChoiceProvider} for exactly when). {@code owner} names the addon in logs and {@code /townkit perf}.
     */
    public static Subscription addChoice(String owner, ChoiceProvider provider) {
        return view().addChoice(owner, provider);
    }

    /**
     * Let a registered activity break in (see {@link InterruptProvider} for when it may). Each villager is asked once
     * every {@code intervalTicks} (at least 10), on its own slot. Throws IllegalArgumentException below 10.
     */
    public static Subscription addInterrupt(String owner, InterruptProvider provider, int intervalTicks) {
        return view().addInterrupt(owner, provider, intervalTicks);
    }
}
