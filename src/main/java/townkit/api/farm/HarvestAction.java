package townkit.api.farm;

import java.util.Objects;

/**
 * What the farmer does to harvest a rule's block.
 *
 * @since 0.8.0
 */
public sealed interface HarvestAction {
    /** Towntopia's own harvest (the default). */
    record Break() implements HarvestAction {
    }

    /** Right-click like a player; the plant stays and grows again. */
    record Use(Holding holding) implements HarvestAction {
        public Use {
            Objects.requireNonNull(holding, "holding");
        }
    }
}
