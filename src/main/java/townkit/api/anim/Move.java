package townkit.api.anim;

import java.util.Locale;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Names a move (an animation a villager plays): one of Towntopia's own by its state name
 * ({@link #towntopia(String)}, e.g. {@code "SALUTE"}, as in Towntopia's {@code VillagerAnimationState}), or an addon
 * move by its id ({@link #of(ResourceLocation)}), from a move file {@code assets/<ns>/townkit/moves/<name>.json}.
 * Towntopia's moves have ids in the {@code towntopia} namespace ({@code towntopia:salute}), so
 * {@code Move.of(towntopia:salute)} is the same move as {@code Move.towntopia("SALUTE")}.
 *
 * <p>Throws NullPointerException on a null id or name; {@link #towntopia(String)} throws IllegalArgumentException on
 * a name that isn't letters, digits and underscores.
 *
 * @since 0.7.0
 */
public record Move(ResourceLocation id) {
    /** The namespace of Towntopia's own moves. */
    private static final String TOWNTOPIA = "towntopia";

    public Move {
        Objects.requireNonNull(id, "id");
    }

    /** Towntopia's own move with this state name (id {@code towntopia:<name in lower case>}). */
    public static Move towntopia(String state) {
        Objects.requireNonNull(state, "state");
        if (state.isEmpty() || !state.chars().allMatch(c -> c == '_' || c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z')) {
            throw new IllegalArgumentException("not a Towntopia move name: '" + state + "'");
        }
        return new Move(ResourceLocation.fromNamespaceAndPath(TOWNTOPIA, state.toLowerCase(Locale.ROOT)));
    }

    /** The move with this id. */
    public static Move of(ResourceLocation id) {
        return new Move(id);
    }

    /** Whether this is one of Towntopia's own moves. */
    public boolean isTowntopia() {
        return TOWNTOPIA.equals(id.getNamespace());
    }

    /** For one of Towntopia's moves, its state name ({@code "SALUTE"}); else the id's path. */
    public String name() {
        return isTowntopia() ? id.getPath().toUpperCase(Locale.ROOT) : id.getPath();
    }
}
