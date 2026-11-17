package townkit.core.work.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.world.item.AirItem;
import net.minecraft.world.item.Item;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkTable;

/**
 * What the smith smelts: "raw X smelts into Y", Towntopia's three (raw iron, gold and copper) plus the addons' and data
 * files' entries, in one {@link WorkTable} keyed by the raw item and merged into one cached {@link View}. Nothing here
 * touches Towntopia's classes, so the unit tests run it without a game; {@link SmeltBridges} and {@link ForgeHooks} read
 * the view.
 *
 * <p>Towntopia's three keep their roles: a {@code change} of their result is honoured, a removal of raw copper is
 * honoured, a removal of raw iron or raw gold is not (iron's count is shared with the smith's iron gathering; gold keeps
 * its kitchen path), so those two stay and one line says so.
 */
public final class SmeltOverlay {
    public static final String KIND = "smelting";

    /** One smelting entry: {@code raw} smelts into {@code result}. */
    public record Smelt(Item raw, Item result) {
    }

    /**
     * The merged state for one table version; immutable.
     *
     * @param touched     something is registered (Towntopia's three alone are not)
     * @param ironIngot   what raw iron smelts into
     * @param goldIngot   what raw gold smelts into
     * @param copperIngot what raw copper smelts into, or null when raw copper was removed
     * @param addons      every entry that is not one of Towntopia's three, in merge order
     * @param ore         Towntopia's own shape of the list ({@code SPARE_ORE}): pairs of raw and result, iron at 0, gold at 1
     *                    (the away path reads it there), copper (unless removed), then the addons
     */
    public record View(boolean touched, Item ironIngot, Item goldIngot, @Nullable Item copperIngot, List<Smelt> addons, Item[][] ore) {
        /** Raw copper is still smelted. */
        public boolean copperOn() {
            return this.copperIngot != null;
        }

        /** The result {@code raw} smelts into (any entry), or null. */
        @Nullable
        public Item resultOf(Item raw) {
            for (Item[] pair : this.ore) {
                if (pair[0] == raw) return pair[1];
            }
            return null;
        }

        /** Whether {@code raw} is an addon entry (not one of Towntopia's three). */
        public boolean isAddon(Item raw) {
            for (Smelt s : this.addons) {
                if (s.raw() == raw) return true;
            }
            return false;
        }
    }

    /** Why {@code raw} into {@code result} can't be an entry (air on either side, or a raw that smelts into itself), or null. */
    @Nullable
    public static String problem(Item raw, Item result) {
        if (raw instanceof AirItem || result instanceof AirItem) return "minecraft:air can't be smelted or made";
        if (raw == result) return "a raw that smelts into itself (" + raw + ") does nothing";
        return null;
    }

    /** The running game's overlay. */
    public static final SmeltOverlay GLOBAL = new SmeltOverlay(new WorkTable<>(KIND, Smelt::raw), TowntopiaSmith::smeltBuiltIns, WorkLog.GLOBAL);

    private final WorkTable<Item, Smelt> table;
    private final Supplier<List<Smelt>> builtInSource;
    private final WorkLog log;
    @Nullable
    private volatile List<Smelt> builtIns;
    @Nullable
    private volatile View view;
    private long viewVersion = -1;

    /** @param builtIns Towntopia's three, in the order iron, gold, copper */
    public SmeltOverlay(WorkTable<Item, Smelt> table, Supplier<List<Smelt>> builtIns, WorkLog log) {
        this.table = table;
        this.builtInSource = builtIns;
        this.log = log;
    }

    public WorkTable<Item, Smelt> table() {
        return this.table;
    }

    /** Hands Towntopia's three entries to the table, once (before data files load). Every reader also calls it. */
    public List<Smelt> loadTowntopia() {
        List<Smelt> b = this.builtIns;
        if (b != null) return b;
        synchronized (this) {
            if (this.builtIns == null) {
                List<Smelt> own = List.copyOf(this.builtInSource.get());
                if (own.size() != 3) throw new IllegalStateException("Towntopia smelts three raws, got " + own.size());
                this.table.builtIns(own);
                this.builtIns = own;
            }
            return this.builtIns;
        }
    }

    public WorkTable.Handle add(String owner, Smelt smelt) {
        loadTowntopia();
        return this.table.add(owner, smelt);
    }

    public WorkTable.Handle change(String owner, Item raw, Item result) {
        loadTowntopia();
        return this.table.change(owner, raw, s -> new Smelt(s.raw(), result));
    }

    public WorkTable.Handle remove(String owner, Item raw) {
        loadTowntopia();
        return this.table.remove(owner, raw);
    }

    /** False while nothing is registered: Towntopia's smith smelts its own three. */
    public boolean touched() {
        return this.table.touched();
    }

    /** The merged view (cached per table version). */
    public View view() {
        loadTowntopia();
        View v = this.view;
        if (v != null && this.viewVersion == this.table.version()) return v;
        return rebuild();
    }

    private synchronized View rebuild() {
        long version = this.table.version();
        View cached = this.view;
        if (cached != null && this.viewVersion == version) return cached;
        List<Smelt> own = loadTowntopia();
        Smelt iron = own.get(0);
        Smelt gold = own.get(1);
        Smelt copper = own.get(2);
        Smelt mergedIron = this.table.get(iron.raw());
        Smelt mergedGold = this.table.get(gold.raw());
        if (mergedIron == null) {
            this.log.once("smelt-unsupported|" + iron.raw(), "[" + KIND + "] removing " + iron.raw()
                + " is unsupported (the smith's iron gathering shares it); it keeps smelting");
            mergedIron = iron;
        }
        if (mergedGold == null) {
            this.log.once("smelt-unsupported|" + gold.raw(), "[" + KIND + "] removing " + gold.raw()
                + " is unsupported (raw gold keeps its kitchen path); it keeps smelting");
            mergedGold = gold;
        }
        Smelt mergedCopper = this.table.get(copper.raw());
        List<Smelt> addons = new ArrayList<>();
        for (Smelt s : this.table.merged()) {
            if (s.raw() != iron.raw() && s.raw() != gold.raw() && s.raw() != copper.raw()) addons.add(s);
        }
        List<Item[]> ore = new ArrayList<>();
        ore.add(new Item[] {mergedIron.raw(), mergedIron.result()});
        ore.add(new Item[] {mergedGold.raw(), mergedGold.result()});
        if (mergedCopper != null) ore.add(new Item[] {mergedCopper.raw(), mergedCopper.result()});
        for (Smelt s : addons) ore.add(new Item[] {s.raw(), s.result()});
        View built = new View(this.table.touched(), mergedIron.result(), mergedGold.result(), mergedCopper == null ? null : mergedCopper.result(),
            List.copyOf(addons), ore.toArray(new Item[0][]));
        this.view = built;
        this.viewVersion = version;
        return built;
    }
}
