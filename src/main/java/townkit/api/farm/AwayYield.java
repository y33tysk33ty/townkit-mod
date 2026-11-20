package townkit.api.farm;

import java.util.Map;
import java.util.Objects;
import net.minecraft.world.item.Item;

/**
 * What a picked plot gives while its village is away (not loaded): {@code min} to {@code max} of {@code item}, and the
 * plot is set to {@code pickedState} afterwards (block property names to values, for example {@code age -> 1}).
 *
 * @param pickedState at least one property, applied to the plot's block state
 * @since 0.8.0
 */
public record AwayYield(Item item, int min, int max, Map<String, String> pickedState) {
    public AwayYield {
        Objects.requireNonNull(item, "item");
        if (min < 0 || max < min) throw new IllegalArgumentException("count must be 0 <= min <= max, not " + min + ".." + max);
        pickedState = Map.copyOf(Objects.requireNonNull(pickedState, "pickedState"));
        if (pickedState.isEmpty()) throw new IllegalArgumentException("pickedState needs at least one property");
    }
}
