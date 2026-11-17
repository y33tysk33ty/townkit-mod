package townkit.api.work;

import java.util.Objects;
import java.util.OptionalInt;
import net.minecraft.resources.ResourceLocation;

/**
 * Where an addon gear tier sits on the smith's tier ladder, for {@link TownKitForge#tier}: right after another tier
 * (Towntopia's {@code towntopia:wood}, {@code towntopia:stone}, {@code towntopia:iron}, {@code towntopia:diamond},
 * {@code towntopia:netherite}, or another addon tier). "Between iron and diamond" is {@code after(towntopia:iron)}; two
 * tiers placed after the same one sit in the order they were registered (the later one higher). Immutable.
 *
 * <p>The smith level a tier needs: {@link #skill(int)}, or by default the level of the next Towntopia tier above it
 * (iron and below: any level; diamond: 25; netherite and above: 75, Towntopia's own levels).
 *
 * @since 0.7.1
 */
public final class TierPlace {
    private final ResourceLocation after;
    private final OptionalInt skill;

    private TierPlace(ResourceLocation after, OptionalInt skill) {
        this.after = after;
        this.skill = skill;
    }

    /** Right after {@code tier}. Throws NullPointerException on a null tier. */
    public static TierPlace after(ResourceLocation tier) {
        return new TierPlace(Objects.requireNonNull(tier, "tier"), OptionalInt.empty());
    }

    /** The same place, with the smith level (1 to 100) the tier needs. Throws IllegalArgumentException outside 1..100. */
    public TierPlace skill(int level) {
        if (level < 1 || level > 100) throw new IllegalArgumentException("skill must be 1 to 100: " + level);
        return new TierPlace(this.after, OptionalInt.of(level));
    }

    /** The tier this one sits right after. */
    public ResourceLocation after() {
        return this.after;
    }

    /** The smith level the tier needs, if set (else the next Towntopia tier's). */
    public OptionalInt skill() {
        return this.skill;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TierPlace t && t.after.equals(this.after) && t.skill.equals(this.skill);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.after, this.skill);
    }

    @Override
    public String toString() {
        return "after " + this.after + (this.skill.isPresent() ? ", skill " + this.skill.getAsInt() : "");
    }
}
