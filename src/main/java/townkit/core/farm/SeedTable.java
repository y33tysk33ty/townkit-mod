package townkit.core.farm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import townkit.api.farm.SeedSource;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkTable;

/**
 * The seeds addon crops are made from: a {@link WorkTable} of kind {@value #KIND} keyed by the seed item. Towntopia's
 * own two (melon and pumpkin seeds) are not in it and stay Towntopia's: an entry that adds, changes or removes one is
 * skipped (logged). Nothing here needs a game; the Towntopia seed set is handed in.
 *
 * @since 0.8.0
 */
public final class SeedTable {
    /** The data-file kind (folder {@code townkit/farm_seeds}). */
    public static final String KIND = "farm_seeds";

    /** One seed and what it is made from. */
    public record Entry(Item seed, Item produce, int seedsPerProduce) {
    }

    /** The running game's table. */
    public static final SeedTable GLOBAL = new SeedTable(new WorkTable<>(KIND, Entry::seed),
        () -> Set.of(Items.MELON_SEEDS, Items.PUMPKIN_SEEDS), WorkLog.GLOBAL);

    private final WorkTable<Item, Entry> table;
    private final Supplier<Set<Item>> towntopiaSeeds;
    private final WorkLog log;
    private volatile long sourcesVersion = -1;
    private volatile Map<Item, SeedSource> sources = Map.of();

    SeedTable(WorkTable<Item, Entry> table, Supplier<Set<Item>> towntopiaSeeds, WorkLog log) {
        this.table = table;
        this.towntopiaSeeds = towntopiaSeeds;
        this.log = log;
    }

    public WorkTable<Item, Entry> table() {
        return this.table;
    }

    /** Whether {@code seed} is one of Towntopia's own two. */
    public boolean isTowntopias(Item seed) {
        return this.towntopiaSeeds.get().contains(seed);
    }

    /** Adds the entry until the handle is removed; an entry that is Towntopia's or has a count under 1 is skipped (logged). */
    public WorkTable.Handle add(String owner, Item seed, Item produce, int seedsPerProduce) {
        if (isTowntopias(seed)) {
            this.log.once("seed-towntopia-add|" + owner + "|" + seed, "[" + KIND + "] " + owner + ": " + seed
                + " is one of Towntopia's own seeds; they stay Towntopia's (entry skipped)");
            return () -> { };
        }
        if (seedsPerProduce < 1) {
            this.log.once("seed-count|" + owner + "|" + seed, "[" + KIND + "] " + owner + ": seeds_per_produce of " + seed
                + " must be at least 1, not " + seedsPerProduce + " (entry skipped)");
            return () -> { };
        }
        return this.table.add(owner, new Entry(seed, produce, seedsPerProduce));
    }

    /** Removes the seed's entry (another addon's or a file's) until the handle is removed; Towntopia's two are skipped (logged). */
    public WorkTable.Handle remove(String owner, Item seed) {
        if (isTowntopias(seed)) {
            this.log.once("seed-towntopia-remove|" + owner + "|" + seed, "[" + KIND + "] " + owner + ": " + seed
                + " is one of Towntopia's own seeds; they stay Towntopia's (removal skipped)");
            return () -> { };
        }
        return this.table.remove(owner, seed);
    }

    /** The merged entries in merge order; immutable. */
    public List<Entry> entries() {
        return this.table.merged();
    }

    @Nullable
    public Entry get(Item seed) {
        return this.table.get(seed);
    }

    /** Whether any addon entry exists. */
    public boolean touched() {
        return !this.table.merged().isEmpty();
    }

    /** The merged entries as the API shows them; immutable, the same instance until something changes. */
    public synchronized Map<Item, SeedSource> sources() {
        long v = this.table.version();
        if (v != this.sourcesVersion) {
            Map<Item, SeedSource> m = new LinkedHashMap<>();
            for (Entry e : this.table.merged()) m.put(e.seed(), new SeedSource(e.produce(), e.seedsPerProduce()));
            this.sources = Collections.unmodifiableMap(m);
            this.sourcesVersion = v;
        }
        return this.sources;
    }
}
