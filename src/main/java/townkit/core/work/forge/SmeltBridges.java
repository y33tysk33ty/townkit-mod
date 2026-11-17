package townkit.core.work.forge;

import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import townkit.core.work.WorkLog;

/**
 * What Towntopia's rewritten smelting instructions call ({@code work-sites.tsv} rows naming this class). While the
 * overlay is off ({@link #active} null: nothing registered, hooks off, or the smith out of reach) every bridge hands back
 * Towntopia's own value after one volatile read.
 *
 * <p>Away catch-up reads Towntopia's list of raws and ingots ({@code SPARE_ORE}), which {@link #onSpareOre} replaces with
 * the merged list. In the village the smith hard-codes its three raws, so addon raws ride its raw-copper path: every
 * count, fetch and consumption of "raw copper" in the spare-ore smelting also covers the addon raws (and not raw copper
 * itself when it was removed), and the ingot made is the one the consumed raw smelts into. The smith's batch size, fuel
 * and the {@code smelt_spare_ore} setting stay Towntopia's, because its own code runs them.
 */
public final class SmeltBridges {
    /** The state the bridges answer from while the smelting overlay is on; null while it is off. */
    @Nullable
    static volatile SmeltOverlay.View active;
    /** The raw {@link #onPick} gives the smith to fetch (set by the stored count just before it; server thread). */
    @Nullable
    private static Item pick;
    /** The ingot of the addon raw {@link #onConsume} just took (read by the ingot read right after it). */
    @Nullable
    private static Item made;

    /** What the furnace step is told to consume while raw copper is removed (an item no smith carries). */
    private static final Item NO_COPPER = Items.BARRIER;

    private SmeltBridges() {
    }

    /** {@code BlacksmithActivity.SPARE_ORE}: the merged pairs of raw and result, gold still at index 1. */
    public static Item[][] onSpareOre(Item[][] towntopia) {
        SmeltOverlay.View v = active;
        return v == null ? towntopia : v.ore();
    }

    /** {@code carriedSpareOre(ctx)}: the raws the smith carries, with the addon raws, and without raw copper when it was removed. */
    public static int onCarried(int towntopia, Object ctx) {
        SmeltOverlay.View v = active;
        if (v == null || !(ctx instanceof Activity.Context c)) return towntopia;
        int n = towntopia;
        if (!v.copperOn()) n -= carried(c, Items.RAW_COPPER);
        List<SmeltOverlay.Smelt> addons = v.addons();
        for (int i = 0, size = addons.size(); i < size; i++) n += carried(c, addons.get(i).raw());
        return n;
    }

    /** {@code countItem(ctx, item)} where the smith sets this load's copper: raw copper counts with the addon raws. */
    public static int onCount(Object ctx, Object item, int towntopia) {
        SmeltOverlay.View v = active;
        if (v == null || item != Items.RAW_COPPER || !(ctx instanceof Activity.Context c)) return towntopia;
        int n = v.copperOn() ? towntopia : 0;
        List<SmeltOverlay.Smelt> addons = v.addons();
        for (int i = 0, size = addons.size(); i < size; i++) n += carried(c, addons.get(i).raw());
        return n;
    }

    /**
     * {@code storedRaw(ctx, item)}: the village's stored raw copper counts with the addon raws; the first one with any in
     * storage is the one {@link #onPick} gives to fetch.
     */
    public static int onStoredRaw(int towntopia, Object ctx, Object item) {
        SmeltOverlay.View v = active;
        if (v == null || item != Items.RAW_COPPER || !(ctx instanceof Activity.Context c)) return towntopia;
        boolean own = v.copperOn() && towntopia > 0;
        int n = v.copperOn() ? towntopia : 0;
        Item first = own ? Items.RAW_COPPER : null;
        List<SmeltOverlay.Smelt> addons = v.addons();
        try {
            for (int i = 0, size = addons.size(); i < size; i++) {
                Item raw = addons.get(i).raw();
                int k = stored(c, raw);
                if (k <= 0) continue;
                n += k;
                if (first == null) first = raw;
            }
        } catch (RuntimeException e) {
            WorkLog.GLOBAL.once("smelt-stored|" + e.getClass().getName(), "[" + SmeltOverlay.KIND + "] counting addon raws in storage failed ("
                + e + "); the smith smelts Towntopia's raws only");
            first = own ? Items.RAW_COPPER : null;
            n = v.copperOn() ? towntopia : 0;
        }
        pick = first;
        return n;
    }

    /** The raw copper the smith fetches ({@code Items.RAW_COPPER} in {@code smeltSpare}): an addon raw when raw copper is out. */
    public static Item onPick(Item copper) {
        Item p = pick;
        pick = null;
        return active == null || p == null ? copper : p;
    }

    /**
     * {@code consumeItems(ctx, item, n)} of raw copper at the furnace: when the smith has none, an addon raw is taken
     * instead, and the ingot read right after is that raw's.
     */
    public static boolean onConsume(boolean towntopia, Object ctx, Object item, int n) {
        SmeltOverlay.View v = active;
        if (v == null || (item != Items.RAW_COPPER && item != NO_COPPER) || !(ctx instanceof Activity.Context c)) return towntopia;
        made = null;
        if (towntopia && item == Items.RAW_COPPER) return true;
        List<SmeltOverlay.Smelt> addons = v.addons();
        for (int i = 0, size = addons.size(); i < size; i++) {
            SmeltOverlay.Smelt s = addons.get(i);
            if (carried(c, s.raw()) >= n && take(c, s.raw(), n)) {
                made = s.result();
                return true;
            }
        }
        return false;
    }

    /**
     * {@code Items.RAW_COPPER} where the furnace step names the raw it consumes: while raw copper is removed, a stand-in
     * Towntopia's own consumption can't find in the smith's pack, so carried raw copper is never smelted ({@link #onConsume}
     * then takes an addon raw instead).
     */
    public static Item onRawCopper(Item towntopia) {
        SmeltOverlay.View v = active;
        return v == null || v.copperOn() ? towntopia : NO_COPPER;
    }

    /** {@code Items.IRON_INGOT} where the smith makes what it smelted: what raw iron smelts into. */
    public static Item onIronIngot(Item towntopia) {
        SmeltOverlay.View v = active;
        return v == null ? towntopia : v.ironIngot();
    }

    /** {@code Items.GOLD_INGOT} where the smith makes what it smelted: what raw gold smelts into. */
    public static Item onGoldIngot(Item towntopia) {
        SmeltOverlay.View v = active;
        return v == null ? towntopia : v.goldIngot();
    }

    /** {@code Items.COPPER_INGOT} where the smith makes what it smelted: the consumed addon raw's, else raw copper's. */
    public static Item onCopperIngot(Item towntopia) {
        SmeltOverlay.View v = active;
        if (v == null) return towntopia;
        Item m = made;
        made = null;
        if (m != null) return m;
        return v.copperIngot() == null ? towntopia : v.copperIngot();
    }

    // --- Towntopia's own counting, as its private helpers do it ------------------------------------------------------------

    private static int carried(Activity.Context c, Item item) {
        InventoryManager inv = c.npc().getInventory();
        int n = 0;
        for (int i = 0; i < 27; i++) {
            ItemStack st = inv.getSlot(i);
            if (st.getItem() == item && inv.mayUseAsMaterial(st)) n += st.getCount();
        }
        return n;
    }

    private static boolean take(Activity.Context c, Item item, int amount) {
        InventoryManager inv = c.npc().getInventory();
        int left = amount;
        for (int i = 0; i < 27 && left > 0; i++) {
            ItemStack st = inv.getSlot(i);
            if (!inv.mayUseAsMaterial(st) || st.getItem() != item) continue;
            int k = Math.min(left, st.getCount());
            inv.reduceSlot(i, k);
            left -= k;
        }
        return left == 0;
    }

    private static int stored(Activity.Context c, Item item) {
        int n = 0;
        for (Building b : c.mgr().getBuildings()) {
            if (b instanceof StorageBuilding storage && storage.isValid()) n += storage.countItem(c.level(), item);
        }
        return n;
    }
}
