package townkit.api.work;

import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * An ore Towntopia's miner (and optionally its druid) knows besides its own seven. Made with {@link #builder};
 * immutable.
 *
 * <ul>
 *   <li>{@code stone} and {@code deepslate}: the ore's blocks. The miner digs them like any ore block (the drops are the
 *   block's own loot, Towntopia's villagers need nothing from TownKit for that); TownKit makes them count as ore for the
 *   miner's ore bonus, vein following and ore sense. A rolled or regrown ore is the deepslate block below Y 0 when there
 *   is one.</li>
 *   <li>{@code label}: the name of the ore's row in the miner's "wanted ores" settings (the block's name when null). The
 *   label shows nowhere else: the mine room and the town statistics have no label for an addon ore, though the mine
 *   room's map marks its blocks like any ore.</li>
 *   <li>{@code depths}: the Y range the miner's choice of mine steers by when this ore's row is switched on, the way
 *   Towntopia's own ores' depths do. Null: switching the row on changes nothing.</li>
 *   <li>{@code luckyRolls}: how often the miner's lucky rolls (the stone it digs turning out to be ore) give this ore.
 *   Null: never.</li>
 *   <li>{@code druid}: how often the druid's regrown rock (a mine it refills) holds this ore. Null: never.</li>
 * </ul>
 *
 * <p><b>Weights</b> are on the scale of Towntopia's own ore placements: a placement's veins times its vein size, spread
 * over {@code low..high} (Towntopia's coal is 30 veins of 17 on Y 136 to 320, a weight of 510; its gold is 4 veins of 9
 * on Y -64 to 32, a weight of 36; addon weights spread evenly, Towntopia's some placements in a triangle). An ore's
 * chance at a height is its share of the ores Towntopia has there plus the addon ores: {@code w / (T + w)}, where
 * {@code w} is the addon ore's weight spread at that height and {@code T} the sum of Towntopia's ore densities there.
 * Only a roll that already produced an ore can turn into an addon ore; Towntopia's own chance of such a roll is
 * untouched.
 *
 * @param id          the ore's id (a mod's namespace, not {@code towntopia:}: Towntopia's seven stay Towntopia's)
 * @param stone       the ore block in stone
 * @param deepslate   the ore block in deepslate, or null
 * @param label       the name of the ore's settings row, or null for the block's name
 * @param depths      the Y range the mine choice steers by, or null
 * @param luckyRolls  the miner's lucky-roll weight, or null
 * @param druid       the druid's placement weight, or null
 * @since 0.7.1
 */
public record OreEntry(ResourceLocation id, Block stone, @Nullable Block deepslate, @Nullable Component label, @Nullable Depths depths,
        @Nullable Spread luckyRolls, @Nullable Spread druid) {
    /** The lowest Y an ore may be given. */
    public static final int MIN_Y = -64;
    /** The highest Y an ore may be given. */
    public static final int MAX_Y = 320;

    /** A Y range, {@code low <= high}, inside {@link #MIN_Y}..{@link #MAX_Y}. */
    public record Depths(int low, int high) {
        public Depths {
            range(low, high);
        }

        /** Whether {@code y} (clamped like Towntopia clamps heights) is in the range. */
        public boolean contains(int y) {
            int c = Math.max(MIN_Y, Math.min(MAX_Y, y));
            return c >= this.low && c <= this.high;
        }
    }

    /** A weight spread evenly over a Y range ({@code weight > 0}, {@code low <= high}, inside {@link #MIN_Y}..{@link #MAX_Y}). */
    public record Spread(double weight, int low, int high) {
        public Spread {
            if (!(weight > 0) || Double.isInfinite(weight)) throw new IllegalArgumentException("weight must be above 0: " + weight);
            range(low, high);
        }

        /** The weight's density at {@code y}: {@code weight / (high - low + 1)} inside the range, 0 outside. */
        public double at(int y) {
            int c = Math.max(MIN_Y, Math.min(MAX_Y, y));
            return c < this.low || c > this.high ? 0 : this.weight / (this.high - this.low + 1);
        }
    }

    private static void range(int low, int high) {
        if (low > high) throw new IllegalArgumentException("low must not be above high: " + low + ", " + high);
        if (low < MIN_Y || high > MAX_Y) {
            throw new IllegalArgumentException("a height must be " + MIN_Y + " to " + MAX_Y + ": " + low + ", " + high);
        }
    }

    public OreEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stone, "stone");
    }

    /** Starts an ore with its id and its block in stone; nothing else is set. */
    public static Builder builder(ResourceLocation id, Block stone) {
        return new Builder(id, stone);
    }

    /** A builder holding this ore's values, to make a changed copy. */
    public Builder toBuilder() {
        Builder b = new Builder(this.id, this.stone);
        b.deepslate = this.deepslate;
        b.label = this.label;
        b.depths = this.depths;
        b.luckyRolls = this.luckyRolls;
        b.druid = this.druid;
        return b;
    }

    /** Builds an {@link OreEntry}. */
    public static final class Builder {
        private final ResourceLocation id;
        private final Block stone;
        @Nullable
        private Block deepslate;
        @Nullable
        private Component label;
        @Nullable
        private Depths depths;
        @Nullable
        private Spread luckyRolls;
        @Nullable
        private Spread druid;

        private Builder(ResourceLocation id, Block stone) {
            this.id = Objects.requireNonNull(id, "id");
            this.stone = Objects.requireNonNull(stone, "stone");
        }

        /** The ore block in deepslate. */
        public Builder deepslate(Block deepslate) {
            this.deepslate = Objects.requireNonNull(deepslate, "deepslate");
            return this;
        }

        /** The name of the ore's row in the miner's "wanted ores" settings. */
        public Builder label(Component label) {
            this.label = Objects.requireNonNull(label, "label");
            return this;
        }

        /** The Y range the miner's choice of mine steers by when the ore is wanted. Throws IllegalArgumentException for a bad range. */
        public Builder depths(int low, int high) {
            this.depths = new Depths(low, high);
            return this;
        }

        /** The miner's lucky-roll weight over {@code low..high}. Throws IllegalArgumentException for a weight at or below 0 or a bad range. */
        public Builder luckyRolls(double weight, int low, int high) {
            this.luckyRolls = new Spread(weight, low, high);
            return this;
        }

        /** The druid's placement weight over {@code low..high}. Throws IllegalArgumentException for a weight at or below 0 or a bad range. */
        public Builder druid(double weight, int low, int high) {
            this.druid = new Spread(weight, low, high);
            return this;
        }

        /** The ore. */
        public OreEntry build() {
            return new OreEntry(this.id, this.stone, this.deepslate, this.label, this.depths, this.luckyRolls, this.druid);
        }
    }
}
