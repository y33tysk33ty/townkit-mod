package townkit.core.work.forge;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.world.item.Item;
import townkit.core.work.WorkLog;

/**
 * What Towntopia's rewritten smith instructions call ({@code work-sites.tsv} rows naming this class). While the forge
 * overlay is off ({@link #active} null: nothing registered, hooks off, or Towntopia's smith out of reach) every bridge
 * hands back Towntopia's own value after one volatile read, so an untouched world runs Towntopia's smith as shipped.
 *
 * <p>Tier bridges translate Towntopia's tier ints (0 wood .. 4 netherite) to positions on TownKit's ladder
 * ({@link ForgeOverlay.View}); with no addon tier the ladder is Towntopia's own and every position equals the int.
 * Material bridges answer the spare material constants ({@code TOWNKIT_0..15}) with the addon materials' items.
 * Stock-recipe bridges answer Towntopia's shield, shears, bucket and template-copy reads with the merged recipes.
 */
public final class ForgeBridges {
    /** The state the bridges answer from while the forge overlay is on; null while it is off. */
    @Nullable
    static volatile ForgeHooks.Active active;
    private static final int TOWNTOPIA_MATS = ForgeOverlay.TOWNTOPIA_MATERIALS.size();

    private ForgeBridges() {
    }

    /** A Towntopia tier int used as a tier gate or cap: its ladder position. */
    public static int onTier(int towntopiaTier) {
        ForgeHooks.Active a = active;
        return a == null ? towntopiaTier : a.view.position(towntopiaTier);
    }

    /** Towntopia's top tier (4) as a loop bound or "no cap": the ladder's top. */
    public static int onTop(int towntopiaTop) {
        ForgeHooks.Active a = active;
        return a == null ? towntopiaTop : a.view.size() - 1;
    }

    /** Towntopia's tier count (5) as an array size: the ladder's length. */
    public static int onSize(int towntopiaSize) {
        ForgeHooks.Active a = active;
        return a == null ? towntopiaSize : a.view.size();
    }

    /** Towntopia's "diamond, as there's no smithing table" (3): the tier just below netherite. */
    public static int onNoTable(int towntopiaTier) {
        ForgeHooks.Active a = active;
        return a == null ? towntopiaTier : a.view.position(4) - 1;
    }

    /**
     * {@code BlacksmithBalance.maxTier(level)} as a ladder position: the highest tier the smith's level allows, Towntopia's
     * tiers by Towntopia's answer, addon tiers by their skill level. Lower addon tiers the level doesn't allow are kept
     * out by the recipe hooks while planning.
     */
    public static int onMaxTier(int level, int towntopiaMax) {
        ForgeHooks.Active a = active;
        if (a == null) return towntopiaMax;
        return maxTier(a.view.ladder, level, towntopiaMax);
    }

    /**
     * The highest ladder position allowed, climbing from the bottom: a Towntopia tier Towntopia doesn't allow ends the
     * climb (an addon tier above it, even with a low skill, would unlock it through the ladder), an addon tier the level
     * doesn't allow is stepped over.
     */
    static int maxTier(List<ForgeOverlay.Rung> ladder, int level, int towntopiaMax) {
        int best = 0;
        for (int p = 1; p < ladder.size(); p++) {
            ForgeOverlay.Rung r = ladder.get(p);
            if (r.towntopia() >= 0) {
                if (r.towntopia() > towntopiaMax) break;
                best = p;
            } else if (level >= r.skill()) {
                best = p;
            }
        }
        return best;
    }

    /** A recipe's tier read for craft points: the ladder position back to Towntopia's tier. */
    public static int onRecipeTier(int position) {
        ForgeHooks.Active a = active;
        return a == null ? position : a.view.towntopiaTier(position);
    }

    /** {@code ForgeOrderBook.tierOf(item)}'s answer: registered gear at its tier, everything else at Towntopia's tier. */
    public static int onTierOf(int towntopiaTier, Object item) {
        ForgeHooks.Active a = active;
        if (a == null) return towntopiaTier;
        int p = item instanceof Item i ? a.view.gearPosition(i) : -1;
        return p >= 0 ? p : a.view.position(towntopiaTier);
    }

    /** {@code Mat.item}: a spare material's first item (null while its slot is empty). */
    @Nullable
    public static Item onMatItem(Object mat, @Nullable Item towntopia) {
        ForgeHooks.Active a = active;
        int slot = a == null ? -1 : ((Enum<?>) mat).ordinal() - TOWNTOPIA_MATS;
        if (slot < 0) return towntopia;
        List<Item> items = a.spareItems.get(slot);
        return items.isEmpty() ? null : items.get(0);
    }

    /** {@code Mat.items}: a spare material's items (empty while its slot is empty). */
    public static List<Item> onMatItems(Object mat, List<Item> towntopia) {
        ForgeHooks.Active a = active;
        int slot = a == null ? -1 : ((Enum<?>) mat).ordinal() - TOWNTOPIA_MATS;
        return slot < 0 ? towntopia : a.spareItems.get(slot);
    }

    /** {@code BlacksmithActivity.storedTotals(ctx)}: Towntopia's ten items, plus the spare materials' items in storage. */
    public static Map<Item, Integer> onStoredTotals(Map<Item, Integer> totals, Object ctx) {
        ForgeHooks.Active a = active;
        if (a == null || a.storedItems.isEmpty() || !(ctx instanceof Activity.Context c)) return totals;
        try {
            for (Building b : c.mgr().getBuildings()) {
                if (!(b instanceof StorageBuilding storage) || !storage.isValid()) continue;
                for (Item item : a.storedItems) {
                    int n = storage.countItem(c.level(), item);
                    if (n > 0) totals.merge(item, n, Integer::sum);
                }
            }
        } catch (RuntimeException e) {
            WorkLog.GLOBAL.once("forge-stored|" + e.getClass().getName(), "[" + ForgeOverlay.MATERIALS
                + "] counting addon materials in storage failed (" + e + "); the smith sees Towntopia's materials only");
        }
        return totals;
    }

    /** {@code BlacksmithActivity.SHIELD}: the merged shield recipe (null when removed). */
    @Nullable
    public static Object onShield(@Nullable Object towntopia) {
        ForgeHooks.Active a = active;
        return a == null ? towntopia : a.shield;
    }

    /** {@code BlacksmithActivity.SHEARS}: the merged shears recipe (null when removed). */
    @Nullable
    public static Object onShears(@Nullable Object towntopia) {
        ForgeHooks.Active a = active;
        return a == null ? towntopia : a.shears;
    }

    /** {@code BlacksmithActivity.BUCKET}: the merged bucket recipe (null when removed). */
    @Nullable
    public static Object onBucket(@Nullable Object towntopia) {
        ForgeHooks.Active a = active;
        return a == null ? towntopia : a.bucket;
    }

    /** {@code BlacksmithActivity.TEMPLATE_COPY}: the merged template-copy recipe (null when removed). */
    @Nullable
    public static Object onTemplateCopy(@Nullable Object towntopia) {
        ForgeHooks.Active a = active;
        return a == null ? towntopia : a.templateCopy;
    }
}
