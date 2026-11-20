package townkit.api.farm;

import java.util.Objects;

/**
 * When a block counts as ripe. The default is {@link Towntopia}: Towntopia's own rule for that crop.
 *
 * @since 0.8.0
 */
public sealed interface Ripe {
    /** Towntopia's own rule (the default): nothing is replaced. */
    record Towntopia() implements Ripe {
    }

    /** An integer block property is at least {@code value} (for example {@code age >= 3}). */
    record AtLeast(String property, int value) implements Ripe {
        public AtLeast {
            Objects.requireNonNull(property, "property");
        }
    }

    /** Any block property equals {@code value}, by its name (a number, {@code true}/{@code false}, an enum name). */
    record Is(String property, String value) implements Ripe {
        public Is {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(value, "value");
        }
    }
}
